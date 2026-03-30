package com.routesense.backend.service.impl;

import com.routesense.backend.dto.RouteRequest;
import com.routesense.backend.dto.TrafficPredictionRequest;
import com.routesense.backend.entity.RouteNode;
import com.routesense.backend.entity.ScheduledTrip;
import com.routesense.backend.entity.ScheduledTripStop;
import com.routesense.backend.optimization.RouteMatrixData;
import com.routesense.backend.optimization.RouteProblem;
import com.routesense.backend.service.ExplanationService;
import com.routesense.backend.service.MapboxApiService;
import com.routesense.backend.service.OsrmService;
import com.routesense.backend.service.ScheduledOptimizationService;
import com.routesense.backend.service.TrafficForecastService;
import com.routesense.backend.util.Emissions;
import org.moeaframework.Executor;
import org.moeaframework.core.NondominatedPopulation;
import org.moeaframework.core.Solution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static com.routesense.backend.util.RouteUtils.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ScheduledOptimizationServiceImpl implements ScheduledOptimizationService {

    @Autowired
    private MapboxApiService mapboxApiService;

    @Autowired
    private OsrmService osrmService;

    @Autowired
    private TrafficForecastService trafficForecastService;

    @Autowired
    private Emissions emissions;

    @Autowired
    private ExplanationService explanationService;

    private static final Logger log = LoggerFactory.getLogger(ScheduledOptimizationServiceImpl.class);

    // ✅ REMOVED instance-level matrices — now created per-request for thread safety

    @Override
    public List<Map<String, Object>> optimizeScheduledTrip(ScheduledTrip trip) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<RouteNode> allPoints = buildRoutePoints(trip);

        int stopCount = trip.getStops() == null ? 0 : trip.getStops().size();

        // ✅ Create matrices locally — thread-safe per request
        RouteMatrixData matrix = new RouteMatrixData(allPoints.size());

        buildScheduledMatrices(allPoints, trip, matrix);

        if (stopCount <= 1) {
            return buildDirectOrSingleStopScheduledRoute(trip, allPoints, matrix);
        }

        RouteProblem problem = new RouteProblem(
                allPoints,
                matrix.getTimeMatrix(),
                matrix.getDistMatrix(),
                matrix.getTrafficRatioMatrix(),
                trip.getPayloadKg(),
                RouteRequest.VehicleType.valueOf(trip.getVehicleType()),
                emissions
        );

        NondominatedPopulation pop = new Executor()
                .withProblem(problem)
                .withAlgorithm("NSGAII")
                .withMaxEvaluations(500)
                .run();

        if (pop == null || pop.isEmpty()) {
            return buildDirectOrSingleStopScheduledRoute(trip, allPoints, matrix);
        }

        Solution fastest = null;
        Solution greenest = null;

        for (Solution s : pop) {
            if (fastest == null || s.getObjective(0) < fastest.getObjective(0)) fastest = s;
            if (greenest == null || s.getObjective(2) < greenest.getObjective(2)) greenest = s;
        }

        Solution main = pickKneeFastGreen(pop);
        if (main == null) main = fastest;

        List<Solution> picked = new ArrayList<>();
        addIfUnique(picked, main);
        addIfUnique(picked, fastest);
        addIfUnique(picked, greenest);

        if (picked.size() < 3) {
            for (Solution s : pop) {
                if (picked.size() >= 3) break;
                addIfUnique(picked, s);
            }
        }

        // If traffic is low and NSGA-II finds < 3 routes, force generate random alternatives for the UI
        int attempts = 0;
        while (picked.size() < 3 && attempts < 20) {
            Solution fallbackSol = problem.newSolution(); // Generates random permutation
            problem.evaluate(fallbackSol); // Calculates Time, Cost, CO2
            addIfUnique(picked, fallbackSol);
            attempts++;
        }

        double mainTime = main.getObjective(0);
        double mainCost = main.getObjective(1);
        double mainCo2 = main.getObjective(2);

        for (Solution sol : picked) {
            double time = sol.getObjective(0);
            double cost = sol.getObjective(1);
            double co2 = sol.getObjective(2);

            boolean isMain = sol.getVariable(0).toString().equals(main.getVariable(0).toString());

            Map<String, Object> routeOption = new HashMap<>();
            routeOption.put("time_seconds", time);
            routeOption.put("cost_currency", cost);
            routeOption.put("co2_emissions", co2);
            routeOption.put("avg_traffic_factor", calculateAverageTrafficFactor(sol, allPoints, matrix));

            if (isMain) {
                routeOption.put("mode", "Recommended (Scheduled Best)");
                routeOption.put("explanation",
                        explanationService.generateExplanation(time, cost, co2, mainTime, mainCo2));
            } else {
                String mode = "Comparison (Scheduled Alternative)";
                if (fastest != null && sol.getVariable(0).toString().equals(fastest.getVariable(0).toString())) {
                    mode = "Comparison (Fastest)";
                } else if (greenest != null && sol.getVariable(0).toString().equals(greenest.getVariable(0).toString())) {
                    mode = "Comparison (Greenest)";
                }

                routeOption.put("mode", mode);
                routeOption.put("explanation",
                        explanationService.generateComparisonExplanation(
                                time - mainTime,
                                cost - mainCost,
                                co2 - mainCo2
                        ));
            }

            List<RouteNode> orderedStops = buildOrderedStops(sol, allPoints);
            routeOption.put("stop_order", extractStopOrderNames(orderedStops));
            routeOption.put("route_sequence", fetchMapboxGeometry(orderedStops));

            results.add(routeOption);
        }

        return results;
    }

    // ===================== HELPERS =====================

    /**
     * Builds matrices using Mapbox base durations + ML-predicted traffic factors for scheduled trips.
     * Falls back to OSRM if Mapbox fails.
     */
    private void buildScheduledMatrices(List<RouteNode> points, ScheduledTrip trip, RouteMatrixData matrix) {
        int size = points.size();

        // 1. Create the Cache (Memory Card)
        Map<String, Double> predictionCache = new HashMap<>();

        try {
            String coordString = buildCoordinateString(points);
            String baseJson = mapboxApiService.fetchMatrixBase(coordString);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(baseJson);
            JsonNode durations = root.path("durations");
            JsonNode distances = root.path("distances");

            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    if (i == j) {
                        matrix.getTimeMatrix()[i][j] = 0.0;
                        matrix.getDistMatrix()[i][j] = 0.0;
                        matrix.getTrafficRatioMatrix()[i][j] = 1.0;
                        continue;
                    }

                    double baseDuration = durations.get(i).get(j).asDouble();
                    double distanceMeters = distances.get(i).get(j).asDouble();
                    double distanceKm = distanceMeters / 1000.0;

                    // 2. Create a unique Key for this exact route segment
                    String segmentKey = points.get(i).getName() + "-" + points.get(j).getName();
                    double predictedFactor = 1.0; // Default

                    // 3. Check the Cache BEFORE calling the ML API
                    if (predictionCache.containsKey(segmentKey)) {
                        predictedFactor = predictionCache.get(segmentKey);
                        // System.out.println("Cache Hit for: " + segmentKey); // Optional debugging
                    } else {
                        TrafficPredictionRequest predReq = new TrafficPredictionRequest(
                                points.get(i).getName(),
                                points.get(j).getName(),
                                points.get(i).getLatitude(),
                                points.get(i).getLongitude(),
                                points.get(j).getLatitude(),
                                points.get(j).getLongitude(),
                                distanceKm,
                                trip.getDepartureTime()
                        );

                        // Call the API
                        predictedFactor = trafficForecastService.predictTrafficFactor(predReq);

                        // 4. Save the new answer to the Cache
                        predictionCache.put(segmentKey, predictedFactor);
                    }

                    matrix.getDistMatrix()[i][j] = distanceMeters;
                    matrix.getTrafficRatioMatrix()[i][j] = predictedFactor;
                    matrix.getTimeMatrix()[i][j] = baseDuration * predictedFactor;
                }
            }
        } catch (Exception e) {
            log.warn("Scheduled matrix build failed. Falling back to OSRM. ", e);
            buildOsrmMatrix(points, 1.20, matrix);
        }
    }

    /**
     * Builds a direct or single-stop route when optimization is not needed.
     */
    private List<Map<String, Object>> buildDirectOrSingleStopScheduledRoute(ScheduledTrip trip,
                                                                            List<RouteNode> allPoints,
                                                                            RouteMatrixData matrix) {
        List<Map<String, Object>> results = new ArrayList<>();

        double totalTime = 0.0;
        double totalCost = 0.0;
        double totalCo2 = 0.0;
        double totalFactor = 0.0;
        int segmentCount = 0;

        for (int i = 0; i < allPoints.size() - 1; i++) {
            double segTime = matrix.getTimeMatrix()[i][i + 1];
            double segDistKm = matrix.getDistMatrix()[i][i + 1] / 1000.0;
            double segRatio = matrix.getTrafficRatioMatrix()[i][i + 1];

            double fuel = emissions.calcFuelLiters(
                    segDistKm,
                    trip.getPayloadKg(),
                    segRatio,
                    RouteRequest.VehicleType.valueOf(trip.getVehicleType())
            );

            totalTime += segTime;
            totalCost += fuel * emissions.getDieselPrice();
            totalCo2 += emissions.calcCo2Kg(
                    segDistKm,
                    trip.getPayloadKg(),
                    segRatio,
                    RouteRequest.VehicleType.valueOf(trip.getVehicleType())
            );

            totalFactor += segRatio;
            segmentCount++;
        }

        Map<String, Object> route = new HashMap<>();
        route.put("mode", "Recommended (Scheduled Direct)");
        route.put("time_seconds", totalTime);
        route.put("cost_currency", totalCost);
        route.put("co2_emissions", totalCo2);
        route.put("avg_traffic_factor", segmentCount == 0 ? 1.0 : totalFactor / segmentCount);
        route.put("explanation", explanationService.generateExplanation(totalTime, totalCost, totalCo2, totalTime, totalCo2));
        route.put("route_sequence", fetchMapboxGeometry(allPoints));
        route.put("stop_order", extractStopOrderNames(allPoints));

        results.add(route);
        return results;
    }

    /**
     * Fallback: builds the matrix using local OSRM with a static traffic factor.
     */
    private void buildOsrmMatrix(List<RouteNode> points, double trafficFactor, RouteMatrixData matrix) {
        int size = points.size();

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (i == j) {
                    matrix.getTimeMatrix()[i][j] = 0.0;
                    matrix.getDistMatrix()[i][j] = 0.0;
                    matrix.getTrafficRatioMatrix()[i][j] = 1.0;
                    continue;
                }

                OsrmService.RouteMetrics m = osrmService.getRouteMetrics(
                        points.get(i).getLatitude(), points.get(i).getLongitude(),
                        points.get(j).getLatitude(), points.get(j).getLongitude(),
                        trafficFactor
                );

                matrix.getTimeMatrix()[i][j] = m.durationSeconds() > 0 ? m.durationSeconds() : Double.MAX_VALUE / 1000;
                matrix.getDistMatrix()[i][j] = m.distanceMeters() > 0 ? m.distanceMeters() : Double.MAX_VALUE / 1000;
                matrix.getTrafficRatioMatrix()[i][j] = trafficFactor;
            }
        }
    }

    /**
     * Fetches full-path geometry from Mapbox Directions API for map rendering.
     */
    private List<Map<String, Object>> fetchMapboxGeometry(List<RouteNode> orderedStops) {
        List<Map<String, Object>> fullPath = new ArrayList<>();
        try {
            String coordString = buildCoordinateString(orderedStops);
            String json = mapboxApiService.fetchDirectionsGeoJson(coordString);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            JsonNode routes = root.path("routes");
            if (!routes.isArray() || routes.isEmpty()) return fullPath;

            JsonNode coords = routes.get(0).path("geometry").path("coordinates");
            if (!coords.isArray()) return fullPath;

            for (JsonNode c : coords) {
                Map<String, Object> p = new HashMap<>();
                p.put("lat", c.get(1).asDouble());
                p.put("lon", c.get(0).asDouble());
                fullPath.add(p);
            }
        } catch (Exception e) {
            log.warn("Geometry fetch failed: " , e);
        }
        return fullPath;
    }

    private List<RouteNode> buildRoutePoints(ScheduledTrip trip) {
        List<RouteNode> points = new ArrayList<>();
        points.add(new RouteNode(trip.getStartName(), trip.getStartLat(), trip.getStartLon()));

        for (ScheduledTripStop s : trip.getStops()) {
            points.add(new RouteNode(s.getStopName(), s.getStopLat(), s.getStopLon()));
        }

        points.add(new RouteNode(trip.getEndName(), trip.getEndLat(), trip.getEndLon()));
        return points;
    }
}