package com.routesense.backend.service.impl;

import com.routesense.backend.dto.RouteRequest;
import com.routesense.backend.entity.RouteNode;
import com.routesense.backend.optimization.RouteMatrixData;
import com.routesense.backend.optimization.RouteProblem;
import com.routesense.backend.service.ExplanationService;
import com.routesense.backend.service.MapboxApiService;
import com.routesense.backend.service.OptimizationService;
import com.routesense.backend.service.OsrmService;
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
public class OptimizationServiceImpl implements OptimizationService {

    @Autowired
    private OsrmService osrmService; // kept for fallback matrix if Mapbox fails

    @Autowired
    private ExplanationService explanationService;

    @Autowired
    private Emissions emissions;

    @Autowired
    private MapboxApiService mapboxApiService;

    private static final Logger log = LoggerFactory.getLogger(OptimizationServiceImpl.class);

    @Override
    public List<Map<String, Object>> findRoutesDynamic(RouteRequest request) {
        List<Map<String, Object>> results = new ArrayList<>();

        int stopCount = (request.getStops() == null) ? 0 : request.getStops().size();
        List<RouteNode> allPoints = buildRoutePoints(request);

        // ✅ Create matrices locally — thread-safe per request
        RouteMatrixData matrix = new RouteMatrixData(allPoints.size());

        // 1) Build Matrix (Mapbox live vs base) OR fallback to OSRM static
        try {
            String coordString = buildCoordinateString(allPoints);

            String liveJson = mapboxApiService.fetchMatrixTraffic(coordString);
            String baseJson = mapboxApiService.fetchMatrixBase(coordString);

            parseMapboxMatrixLiveVsBase(liveJson, baseJson, allPoints, matrix);

        } catch (Exception e) {
            log.warn("Mapbox failed. Falling back to static OSRM.", e);
            buildOsrmMatrix(allPoints, request.getTrafficFactor(), matrix);
        }

        // MODE 1: Direct Path (no stops)
        if (stopCount == 0) {
            double time = matrix.getTimeMatrix()[0][1];
            double distKm = matrix.getDistMatrix()[0][1] / 1000.0;
            double ratio = matrix.getTrafficRatioMatrix()[0][1];

            double fuel = emissions.calcFuelLiters(distKm, request.getWeightKg(), ratio, request.getVehicleType());
            double cost = fuel * emissions.getDieselPrice();
            double co2 = emissions.calcCo2Kg(distKm, request.getWeightKg(), ratio, request.getVehicleType());

            Map<String, Object> option = new HashMap<>();
            option.put("mode", "Recommended (Direct)");
            option.put("time_seconds", time);
            option.put("cost_currency", cost);
            option.put("co2_emissions", co2);
            option.put("explanation", explanationService.generateExplanation(time, cost, co2, time, co2));
            option.put("route_sequence", fetchMapboxGeometry(List.of(allPoints.get(0), allPoints.get(1))));
            option.put("avg_traffic_factor", matrix.getTrafficRatioMatrix()[0][1]);
            results.add(option);
            return results;
        }

        // MODE 2: NSGA-II optimization (stops reorder)
        RouteProblem problem = new RouteProblem(
                allPoints, matrix.getTimeMatrix(), matrix.getDistMatrix(), matrix.getTrafficRatioMatrix(),
                request.getWeightKg(), request.getVehicleType(), emissions
        );

        // Inside optimizeScheduledTrip method
        NondominatedPopulation pop = new Executor()
                .withProblem(problem)
                .withAlgorithm("NSGAII")
                .withProperty("populationSize", 200)
                .withMaxEvaluations(2000)
                .run();

        if (pop == null || pop.isEmpty()) {
            results.add(buildSimpleFallback(allPoints, request, matrix));
            return results;
        }

        // 2) Fastest + Greenest
        Solution fastest = null;
        Solution greenest = null;

        for (Solution s : pop) {
            if (fastest == null || s.getObjective(0) < fastest.getObjective(0)) fastest = s;
            if (greenest == null || s.getObjective(2) < greenest.getObjective(2)) greenest = s;
        }

        // 3) Main = knee between time and CO2
        Solution main = pickKneeFastGreen(pop);
        if (main == null) main = fastest;

        // 4) Pick 1 main + 2 comparisons (unique)
        List<Solution> picked = new ArrayList<>();
        addIfUnique(picked, main);
        addIfUnique(picked, fastest);
        addIfUnique(picked, greenest);

        // If duplicates, fill remaining from population
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
        // Main metrics (for comparison)
        double mainTime = main.getObjective(0);
        double mainCost = main.getObjective(1);
        double mainCo2 = main.getObjective(2);

        // 5) Return routes: main labeled as "Fastest + Greenest", others are comparison routes
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
                routeOption.put("mode", "Recommended (Fastest + Greenest)");
                routeOption.put("explanation",
                        explanationService.generateExplanation(time, cost, co2, mainTime, mainCo2)
                );
            } else {
                String mode = "Comparison (Balanced)";
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
                        )
                );
            }

            // Geometry only fetched for these up-to-3 routes
            List<RouteNode> orderedStops = buildOrderedStops(sol, allPoints);
            routeOption.put("route_sequence", fetchMapboxGeometry(orderedStops));

            // Optional: return stop order for research display
            routeOption.put("stop_order", extractStopOrderNames(orderedStops));

            results.add(routeOption);
        }

        return results;
    }

    // ===================== HELPERS =====================

    /**
     * Parses Mapbox live-traffic and base-traffic matrix responses,
     * computing the traffic ratio per segment.
     */
    private void parseMapboxMatrixLiveVsBase(String liveJsonStr, String baseJsonStr,
                                             List<RouteNode> allPoints, RouteMatrixData matrix) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            int size = allPoints.size();

            JsonNode liveRoot = mapper.readTree(liveJsonStr);
            JsonNode baseRoot = mapper.readTree(baseJsonStr);

            JsonNode liveDurations = liveRoot.path("durations");
            JsonNode liveDistances = liveRoot.path("distances");
            JsonNode baseDurations = baseRoot.path("durations");

            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {

                    if (i == j) {
                        matrix.getTimeMatrix()[i][j] = 0.0;
                        matrix.getDistMatrix()[i][j] = 0.0;
                        matrix.getTrafficRatioMatrix()[i][j] = 1.0;
                        continue;
                    }

                    JsonNode liveDurNode = liveDurations.get(i).get(j);
                    JsonNode liveDistNode = liveDistances.get(i).get(j);
                    JsonNode baseDurNode = baseDurations.get(i).get(j);

                    if (liveDurNode == null || baseDurNode == null || liveDistNode == null
                            || liveDurNode.isNull() || baseDurNode.isNull() || liveDistNode.isNull()) {
                        matrix.getTimeMatrix()[i][j] = Double.MAX_VALUE / 1000;
                        matrix.getDistMatrix()[i][j] = Double.MAX_VALUE / 1000;
                        matrix.getTrafficRatioMatrix()[i][j] = 1.0;
                        continue;
                    }

                    double liveTime = liveDurNode.asDouble();
                    double baseTime = baseDurNode.asDouble();
                    double dist = liveDistNode.asDouble();

                    if (liveTime <= 0 || baseTime <= 0 || dist <= 0) {
                        matrix.getTimeMatrix()[i][j] = Double.MAX_VALUE / 1000;
                        matrix.getDistMatrix()[i][j] = dist > 0 ? dist : Double.MAX_VALUE / 1000;
                        matrix.getTrafficRatioMatrix()[i][j] = 1.0;
                        continue;
                    }

                    double ratio = liveTime / baseTime;

                    matrix.getTimeMatrix()[i][j] = liveTime;
                    matrix.getDistMatrix()[i][j] = dist;
                    matrix.getTrafficRatioMatrix()[i][j] = Math.max(1.0, ratio);
                }
            }
        } catch (Exception e) {
            log.error("Matrix parsing failed (Mapbox live vs base).", e);
        }
    }

    /**
     * Fallback: builds the matrix using local OSRM with a static traffic factor.
     */
    private void buildOsrmMatrix(List<RouteNode> points, double trafficFactor, RouteMatrixData matrix) {
        int size = points.size();

        double safeFactor = (trafficFactor > 0) ? trafficFactor : 1.0;

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (i == j) {
                    matrix.getTrafficRatioMatrix()[i][j] = 1.0;
                    continue;
                }

                OsrmService.RouteMetrics m = osrmService.getRouteMetrics(
                        points.get(i).getLatitude(), points.get(i).getLongitude(),
                        points.get(j).getLatitude(), points.get(j).getLongitude(),
                        safeFactor
                );

                matrix.getTimeMatrix()[i][j] = m.durationSeconds();
                matrix.getDistMatrix()[i][j] = m.distanceMeters();
                matrix.getTrafficRatioMatrix()[i][j] = safeFactor;
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

            JsonNode geometry = routes.get(0).path("geometry");
            JsonNode coords = geometry.path("coordinates"); // [[lon,lat],[lon,lat],...]

            if (!coords.isArray()) return fullPath;

            for (JsonNode c : coords) {
                double lon = c.get(0).asDouble();
                double lat = c.get(1).asDouble();

                Map<String, Object> p = new HashMap<>();
                p.put("lat", lat);
                p.put("lon", lon);
                fullPath.add(p);
            }

        } catch (Exception e) {
            log.warn("Mapbox Directions geometry failed.", e);
        }
        return fullPath;
    }

    /**
     * Builds a simple fallback route when NSGA-II produces no results.
     */
    private Map<String, Object> buildSimpleFallback(List<RouteNode> allPoints, RouteRequest request,
                                                    RouteMatrixData matrix) {
        List<RouteNode> ordered = new ArrayList<>(allPoints);

        double totalTime = 0, totalCost = 0, totalCo2 = 0;
        for (int i = 0; i < ordered.size() - 1; i++) {
            double segTime = matrix.getTimeMatrix()[i][i + 1];
            double segDistKm = matrix.getDistMatrix()[i][i + 1] / 1000.0;
            double segRatio = matrix.getTrafficRatioMatrix()[i][i + 1];

            double fuel = emissions.calcFuelLiters(segDistKm, request.getWeightKg(), segRatio, request.getVehicleType());
            totalTime += segTime;
            totalCost += fuel * emissions.getDieselPrice();
            totalCo2 += emissions.calcCo2Kg(segDistKm, request.getWeightKg(), segRatio, request.getVehicleType());
        }

        Map<String, Object> option = new HashMap<>();
        option.put("mode", "Recommended (Fallback)");
        option.put("time_seconds", totalTime);
        option.put("cost_currency", totalCost);
        option.put("co2_emissions", totalCo2);
        option.put("explanation", explanationService.generateExplanation(totalTime, totalCost, totalCo2, totalTime, totalCo2));
        option.put("route_sequence", fetchMapboxGeometry(ordered));
        option.put("stop_order", extractStopOrderNames(ordered));
        return option;
    }

    private List<RouteNode> buildRoutePoints(RouteRequest request) {
        List<RouteNode> points = new ArrayList<>();
        points.add(new RouteNode(request.getStartName(), request.getStartLat(), request.getStartLon()));

        if (request.getStops() != null) {
            for (RouteRequest.Waypoint wp : request.getStops()) {
                points.add(new RouteNode(wp.getName(), wp.getLatitude(), wp.getLongitude()));
            }
        }

        points.add(new RouteNode(request.getEndName(), request.getEndLat(), request.getEndLon()));
        return points;
    }



}