package com.routesense.backend.service.impl;

import com.routesense.backend.dto.RouteRequest;
import com.routesense.backend.dto.TrafficPredictionRequest;
import com.routesense.backend.entity.RouteNode;
import com.routesense.backend.entity.ScheduledTrip;
import com.routesense.backend.entity.ScheduledTripStop;
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
import org.moeaframework.core.variable.Permutation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

    private double[][] timeMatrix;
    private double[][] distMatrix;
    private double[][] trafficRatioMatrix;

    @Override
    public List<Map<String, Object>> optimizeScheduledTrip(ScheduledTrip trip) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<RouteNode> allPoints = buildRoutePoints(trip);

        int stopCount = trip.getStops() == null ? 0 : trip.getStops().size();

        buildScheduledMatrices(allPoints, trip);

        if (stopCount <= 1) {
            return buildDirectOrSingleStopScheduledRoute(trip, allPoints);
        }

        RouteProblem problem = new RouteProblem(
                allPoints,
                timeMatrix,
                distMatrix,
                trafficRatioMatrix,
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
            return buildDirectOrSingleStopScheduledRoute(trip, allPoints);
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
            routeOption.put("avg_traffic_factor", calculateAverageTrafficFactor(sol, allPoints));

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

    private void buildScheduledMatrices(List<RouteNode> points, ScheduledTrip trip) {
        int size = points.size();
        timeMatrix = new double[size][size];
        distMatrix = new double[size][size];
        trafficRatioMatrix = new double[size][size];

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
                        timeMatrix[i][j] = 0.0;
                        distMatrix[i][j] = 0.0;
                        trafficRatioMatrix[i][j] = 1.0;
                        continue;
                    }

                    double baseDuration = durations.get(i).get(j).asDouble();
                    double distanceMeters = distances.get(i).get(j).asDouble();
                    double distanceKm = distanceMeters / 1000.0;

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

                    double predictedFactor = trafficForecastService.predictTrafficFactor(predReq);

                    distMatrix[i][j] = distanceMeters;
                    trafficRatioMatrix[i][j] = predictedFactor;
                    timeMatrix[i][j] = baseDuration * predictedFactor;
                }
            }
        } catch (Exception e) {
            System.err.println("Scheduled matrix build failed. Falling back to OSRM. " + e.getMessage());
            buildOsrmMatrix(points, 1.20);
        }
    }

    private List<Map<String, Object>> buildDirectOrSingleStopScheduledRoute(ScheduledTrip trip, List<RouteNode> allPoints) {
        List<Map<String, Object>> results = new ArrayList<>();

        double totalTime = 0.0;
        double totalCost = 0.0;
        double totalCo2 = 0.0;
        double totalFactor = 0.0;
        int segmentCount = 0;

        for (int i = 0; i < allPoints.size() - 1; i++) {
            double segTime = timeMatrix[i][i + 1];
            double segDistKm = distMatrix[i][i + 1] / 1000.0;
            double segRatio = trafficRatioMatrix[i][i + 1];

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

    private void buildOsrmMatrix(List<RouteNode> points, double trafficFactor) {
        int size = points.size();
        timeMatrix = new double[size][size];
        distMatrix = new double[size][size];
        trafficRatioMatrix = new double[size][size];

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (i == j) {
                    timeMatrix[i][j] = 0.0;
                    distMatrix[i][j] = 0.0;
                    trafficRatioMatrix[i][j] = 1.0;
                    continue;
                }

                OsrmService.RouteMetrics m = osrmService.getRouteMetrics(
                        points.get(i).getLatitude(), points.get(i).getLongitude(),
                        points.get(j).getLatitude(), points.get(j).getLongitude(),
                        trafficFactor
                );

                timeMatrix[i][j] = m.durationSeconds() > 0 ? m.durationSeconds() : Double.MAX_VALUE / 1000;
                distMatrix[i][j] = m.distanceMeters() > 0 ? m.distanceMeters() : Double.MAX_VALUE / 1000;
                trafficRatioMatrix[i][j] = trafficFactor;
            }
        }
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

    private String buildCoordinateString(List<RouteNode> points) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            sb.append(points.get(i).getLongitude()).append(",").append(points.get(i).getLatitude());
            if (i < points.size() - 1) sb.append(";");
        }
        return sb.toString();
    }

    private List<RouteNode> buildOrderedStops(Solution solution, List<RouteNode> allOrders) {
        int[] permutation = ((Permutation) solution.getVariable(0)).toArray();
        List<RouteNode> orderedStops = new ArrayList<>();
        orderedStops.add(allOrders.get(0));
        for (int index : permutation) {
            orderedStops.add(allOrders.get(index + 1));
        }
        orderedStops.add(allOrders.get(allOrders.size() - 1));
        return orderedStops;
    }

    private List<String> extractStopOrderNames(List<RouteNode> orderedStops) {
        List<String> names = new ArrayList<>();
        for (RouteNode n : orderedStops) names.add(n.getName());
        return names;
    }

    private void addIfUnique(List<Solution> list, Solution candidate) {
        if (candidate == null) return;
        String perm = candidate.getVariable(0).toString();
        for (Solution s : list) {
            if (s.getVariable(0).toString().equals(perm)) return;
        }
        list.add(candidate);
    }

    private Solution pickKneeFastGreen(NondominatedPopulation pop) {
        double minT = Double.MAX_VALUE, maxT = -Double.MAX_VALUE;
        double minC = Double.MAX_VALUE, maxC = -Double.MAX_VALUE;

        for (Solution s : pop) {
            minT = Math.min(minT, s.getObjective(0));
            maxT = Math.max(maxT, s.getObjective(0));
            minC = Math.min(minC, s.getObjective(2));
            maxC = Math.max(maxC, s.getObjective(2));
        }

        double tRange = Math.max(1e-9, maxT - minT);
        double cRange = Math.max(1e-9, maxC - minC);

        Solution best = null;
        double bestScore = Double.MAX_VALUE;

        for (Solution s : pop) {
            double tNorm = (s.getObjective(0) - minT) / tRange;
            double cNorm = (s.getObjective(2) - minC) / cRange;
            double score = tNorm + cNorm;

            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private double calculateAverageTrafficFactor(Solution solution, List<RouteNode> allOrders) {
        int[] permutation = ((Permutation) solution.getVariable(0)).toArray();

        List<Integer> pathIndices = new ArrayList<>();
        pathIndices.add(0);
        for (int index : permutation) {
            pathIndices.add(index + 1);
        }
        pathIndices.add(allOrders.size() - 1);

        double total = 0.0;
        int count = 0;

        for (int i = 0; i < pathIndices.size() - 1; i++) {
            int fromIdx = pathIndices.get(i);
            int toIdx = pathIndices.get(i + 1);
            total += trafficRatioMatrix[fromIdx][toIdx];
            count++;
        }

        return count == 0 ? 1.0 : total / count;
    }

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
            System.err.println("Geometry fetch failed: " + e.getMessage());
        }
        return fullPath;
    }
}