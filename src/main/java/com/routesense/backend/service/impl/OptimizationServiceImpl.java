package com.routesense.backend.service.impl;

import com.routesense.backend.dto.RouteRequest;
import com.routesense.backend.entity.RouteNode;
import com.routesense.backend.optimization.RouteProblem;
import com.routesense.backend.service.ExplanationService;
import com.routesense.backend.service.MapboxApiService;
import com.routesense.backend.service.OptimizationService;
import com.routesense.backend.service.OsrmService;
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
public class OptimizationServiceImpl implements OptimizationService {

    @Autowired
    private OsrmService osrmService;

    @Autowired
    private ExplanationService explanationService;

    @Autowired
    private Emissions emissions;

    @Autowired
    private MapboxApiService mapboxApiService;

    private double[][] timeMatrix;
    private double[][] distMatrix;
    private double[][] trafficRatioMatrix; // NEW: Added the traffic ratio matrix

    @Override
    public List<Map<String, Object>> findRoutesDynamic(RouteRequest request) {
        List<Map<String, Object>> results = new ArrayList<>();

        int stopCount = (request.getStops() == null) ? 0 : request.getStops().size();
        int totalLocs = stopCount + 2;

        boolean useGoogleMaps = true;

        List<RouteNode> allPoints = buildRoutePoints(request);

        // 1. Fetch or Build Matrix First
        try {
            String coordString = buildCoordinateString(allPoints);
            String json = mapboxApiService.fetchMatrix(coordString);
            // Pass allPoints here to enable the dynamic comparison
            parseMapboxMatrix(json, allPoints);
        } catch (Exception e) {
            System.err.println("Mapbox failed. Falling back to static OSRM.");
            buildOsrmMatrix(allPoints, request.getTrafficFactor());
        }

        // MODE 1: Direct Path
        if (stopCount == 0) {
            double time = timeMatrix[0][1];
            double distKm = distMatrix[0][1] / 1000.0;
            double currentRatio = trafficRatioMatrix[0][1]; // Get specific ratio for the direct path

            double fuel = emissions.calcFuelLiters(distKm, request.getWeightKg(), currentRatio, request.getVehicleType());
            double cost = fuel * emissions.getDieselPrice();
            double co2 = emissions.calcCo2Kg(distKm, request.getWeightKg(), currentRatio, request.getVehicleType());

            Map<String, Object> option = new HashMap<>();
            option.put("mode", "Direct Path");
            option.put("time_seconds", time);
            option.put("cost_currency", cost);
            option.put("co2_emissions", co2);
            option.put("explanation", explanationService.generateExplanation(time, cost, co2, time, co2));
            option.put("route_sequence", fetchFullRouteGeometryForDirect(allPoints.get(0), allPoints.get(1)));
            results.add(option);
            return results;
        }

        // MODE 2: Multi-Stop Optimization (NSGA-II)
        // Pass the new trafficRatioMatrix and the emissions service into the problem
        RouteProblem problem = new RouteProblem(
                allPoints, timeMatrix, distMatrix, trafficRatioMatrix,
                request.getWeightKg(), request.getVehicleType(), emissions
        );

        NondominatedPopulation pop = new Executor()
                .withProblem(problem)
                .withAlgorithm("NSGAII")
                .withMaxEvaluations(500)
                .run();

        List<String> foundPerms = new ArrayList<>();
        double bestTime = Double.MAX_VALUE;
        double bestCo2 = Double.MAX_VALUE;

        // Process Optimal Routes
        for (Solution solution : pop) {
            String permString = solution.getVariable(0).toString();
            if (foundPerms.contains(permString)) continue;
            foundPerms.add(permString);

            double time = solution.getObjective(0);
            double cost = solution.getObjective(1);
            double co2 = solution.getObjective(2);

            if (time < bestTime) bestTime = time;
            if (co2 < bestCo2) bestCo2 = co2;

            Map<String, Object> routeOption = new HashMap<>();
            routeOption.put("mode", "Recommended (Optimal)");
            routeOption.put("time_seconds", time);
            routeOption.put("cost_currency", cost);
            routeOption.put("co2_emissions", co2);
            routeOption.put("explanation", explanationService.generateExplanation(time, cost, co2, bestTime, bestCo2));
            routeOption.put("route_sequence", fetchFullRouteGeometry(solution, allPoints, useGoogleMaps));

            results.add(routeOption);
        }

        return results;
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

    private String buildCoordinateString(List<RouteNode> points) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            sb.append(points.get(i).getLongitude()).append(",").append(points.get(i).getLatitude());
            if (i < points.size() - 1) sb.append(";");
        }
        return sb.toString();
    }

    private void parseMapboxMatrix(String jsonStr, List<RouteNode> allPoints) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            int size = allPoints.size();
            timeMatrix = new double[size][size];
            distMatrix = new double[size][size];
            trafficRatioMatrix = new double[size][size];

            JsonNode root = mapper.readTree(jsonStr);
            JsonNode durations = root.path("durations");
            JsonNode distances = root.path("distances");

            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    if (i == j) {
                        trafficRatioMatrix[i][j] = 1.0;
                        continue;
                    }

                    double liveTime = durations.get(i).get(j).asDouble();
                    double dist = distances.get(i).get(j).asDouble();

                    // Get "Perfect World" time from OSRM (factor 1.0)
                    OsrmService.RouteMetrics base = osrmService.getRouteMetrics(
                            allPoints.get(i).getLatitude(), allPoints.get(i).getLongitude(),
                            allPoints.get(j).getLatitude(), allPoints.get(j).getLongitude(),
                            1.0
                    );

                    double baseTime = base.durationSeconds();

                    // Calculate the live ratio: Live / Base
                    // If live is 15 mins and base is 10 mins, ratio is 1.5
                    double dynamicRatio = (baseTime > 0) ? (liveTime / baseTime) : 1.0;

                    timeMatrix[i][j] = liveTime;
                    distMatrix[i][j] = dist;
                    trafficRatioMatrix[i][j] = Math.max(1.0, dynamicRatio);
                }
            }
        } catch (Exception e) {
            System.err.println("Dynamic Matrix parsing failed.");
        }
    }

    private void buildOsrmMatrix(List<RouteNode> points, double trafficFactor) {
        int size = points.size();
        timeMatrix = new double[size][size];
        distMatrix = new double[size][size];
        trafficRatioMatrix = new double[size][size]; // Initialize array for fallback

        // If fallback occurs, the ratio becomes the static factor the user provided
        double safeFactor = (trafficFactor > 0) ? trafficFactor : 1.0;

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (i == j) {
                    trafficRatioMatrix[i][j] = 1.0;
                    continue;
                }

                OsrmService.RouteMetrics m = osrmService.getRouteMetrics(
                        points.get(i).getLatitude(), points.get(i).getLongitude(),
                        points.get(j).getLatitude(), points.get(j).getLongitude(),
                        safeFactor
                );

                timeMatrix[i][j] = m.durationSeconds();
                distMatrix[i][j] = m.distanceMeters();
                trafficRatioMatrix[i][j] = safeFactor; // Store fallback factor
            }
        }
    }

    private List<Map<String, Object>> fetchFullRouteGeometryForDirect(RouteNode from, RouteNode to) {
        List<Map<String, Object>> fullPath = new ArrayList<>();
        Map<String, Object> segment = osrmService.getRoute(
                from.getLatitude(), from.getLongitude(),
                to.getLatitude(), to.getLongitude()
        );
        if (segment != null && segment.containsKey("geometry")) {
            fullPath.addAll(decodePolyline((String) segment.get("geometry")));
        }
        return fullPath;
    }

    private List<Map<String, Object>> fetchFullRouteGeometry(Solution solution, List<RouteNode> allOrders, boolean useGoogleMaps) {
        List<Map<String, Object>> fullPath = new ArrayList<>();
        int[] permutation = ((Permutation) solution.getVariable(0)).toArray();

        List<RouteNode> orderedStops = new ArrayList<>();
        orderedStops.add(allOrders.get(0));
        for (int index : permutation) {
            orderedStops.add(allOrders.get(index + 1));
        }
        if (allOrders.size() > 1) {
            orderedStops.add(allOrders.get(allOrders.size() - 1));
        }

        for (int i = 0; i < orderedStops.size() - 1; i++) {
            RouteNode from = orderedStops.get(i);
            RouteNode to = orderedStops.get(i + 1);

            Map<String, Object> segment = osrmService.getRoute(
                    from.getLatitude(), from.getLongitude(),
                    to.getLatitude(), to.getLongitude()
            );

            if (segment != null && segment.containsKey("geometry")) {
                fullPath.addAll(decodePolyline((String) segment.get("geometry")));
            }
        }
        return fullPath;
    }

    private List<Map<String, Object>> decodePolyline(String encoded) {
        List<Map<String, Object>> poly = new ArrayList<>();
        int index = 0, len = encoded.length();
        int lat = 0, lng = 0;

        while (index < len) {
            int b, shift = 0, result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlat = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lat += dlat;

            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlng = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lng += dlng;

            Map<String, Object> p = new HashMap<>();
            p.put("lat", (double) lat / 1E5);
            p.put("lon", (double) lng / 1E5);
            poly.add(p);
        }
        return poly;
    }
}