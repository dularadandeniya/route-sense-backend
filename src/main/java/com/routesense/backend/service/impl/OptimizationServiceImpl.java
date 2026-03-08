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
    private OsrmService osrmService; // kept for fallback matrix if Mapbox fails

    @Autowired
    private ExplanationService explanationService;

    @Autowired
    private Emissions emissions;

    @Autowired
    private MapboxApiService mapboxApiService;

    private double[][] timeMatrix;
    private double[][] distMatrix;
    private double[][] trafficRatioMatrix;

    @Override
    public List<Map<String, Object>> findRoutesDynamic(RouteRequest request) {
        List<Map<String, Object>> results = new ArrayList<>();

        int stopCount = (request.getStops() == null) ? 0 : request.getStops().size();
        List<RouteNode> allPoints = buildRoutePoints(request);

        // 1) Build Matrix (Mapbox live vs base) OR fallback to OSRM static
        try {
            String coordString = buildCoordinateString(allPoints);

            String liveJson = mapboxApiService.fetchMatrixTraffic(coordString);
            String baseJson = mapboxApiService.fetchMatrixBase(coordString);

            parseMapboxMatrixLiveVsBase(liveJson, baseJson, allPoints);

        } catch (Exception e) {
            System.err.println("Mapbox failed. Falling back to static OSRM. " + e.getMessage());
            buildOsrmMatrix(allPoints, request.getTrafficFactor());
        }


        // MODE 1: Direct Path (no stops)
        if (stopCount == 0) {
            double time = timeMatrix[0][1];
            double distKm = distMatrix[0][1] / 1000.0;
            double ratio = trafficRatioMatrix[0][1];

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
            option.put("avg_traffic_factor", calculateDirectTrafficFactor());
            results.add(option);
            return results;
        }

        // MODE 2: NSGA-II optimization (stops reorder)
        RouteProblem problem = new RouteProblem(
                allPoints, timeMatrix, distMatrix, trafficRatioMatrix,
                request.getWeightKg(), request.getVehicleType(), emissions
        );

        NondominatedPopulation pop = new Executor()
                .withProblem(problem)
                .withAlgorithm("NSGAII")
                .withMaxEvaluations(500)
                .run();

        if (pop == null || pop.isEmpty()) {
            results.add(buildSimpleFallback(allPoints, request));
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

        // Main metrics (for comparison)
        double mainTime = main.getObjective(0);
        double mainCost = main.getObjective(1);
        double mainCo2  = main.getObjective(2);

        // 5) Return routes: main labeled as "Fastest + Greenest", others are comparison routes
        for (Solution sol : picked) {
            double time = sol.getObjective(0);
            double cost = sol.getObjective(1);
            double co2  = sol.getObjective(2);

            boolean isMain = sol.getVariable(0).toString().equals(main.getVariable(0).toString());

            Map<String, Object> routeOption = new HashMap<>();
            routeOption.put("time_seconds", time); 
            routeOption.put("cost_currency", cost);
            routeOption.put("co2_emissions", co2);
            routeOption.put("avg_traffic_factor", calculateAverageTrafficFactorForSolution(sol, allPoints));

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

    // ------------------- helpers -------------------

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

    private List<RouteNode> buildOrderedStops(Solution solution, List<RouteNode> allOrders) {
        int[] permutation = ((Permutation) solution.getVariable(0)).toArray();

        List<RouteNode> orderedStops = new ArrayList<>();
        orderedStops.add(allOrders.get(0)); // start
        for (int index : permutation) {
            orderedStops.add(allOrders.get(index + 1)); // middle stops
        }
        orderedStops.add(allOrders.get(allOrders.size() - 1)); // end
        return orderedStops;
    }

    private List<String> extractStopOrderNames(List<RouteNode> orderedStops) {
        List<String> names = new ArrayList<>();
        for (RouteNode n : orderedStops) names.add(n.getName());
        return names;
    }

    private void addIfUnique(List<Solution> list, Solution candidate) {
        if (candidate == null) return;
        String candPerm = candidate.getVariable(0).toString();
        for (Solution s : list) {
            if (s.getVariable(0).toString().equals(candPerm)) return;
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

            double score = tNorm + cNorm; // equal weights
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private void parseMapboxMatrixLiveVsBase(String liveJsonStr, String baseJsonStr, List<RouteNode> allPoints) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            int size = allPoints.size();

            timeMatrix = new double[size][size];
            distMatrix = new double[size][size];
            trafficRatioMatrix = new double[size][size];

            JsonNode liveRoot = mapper.readTree(liveJsonStr);
            JsonNode baseRoot = mapper.readTree(baseJsonStr);

            JsonNode liveDurations = liveRoot.path("durations");
            JsonNode liveDistances = liveRoot.path("distances");
            JsonNode baseDurations = baseRoot.path("durations");

            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {

                    if (i == j) {
                        timeMatrix[i][j] = 0.0;
                        distMatrix[i][j] = 0.0;
                        trafficRatioMatrix[i][j] = 1.0;
                        continue;
                    }

                    JsonNode liveDurNode = liveDurations.get(i).get(j);
                    JsonNode liveDistNode = liveDistances.get(i).get(j);
                    JsonNode baseDurNode = baseDurations.get(i).get(j);

                    if (liveDurNode == null || baseDurNode == null || liveDistNode == null
                            || liveDurNode.isNull() || baseDurNode.isNull() || liveDistNode.isNull()) {
                        timeMatrix[i][j] = Double.MAX_VALUE / 1000;
                        distMatrix[i][j] = Double.MAX_VALUE / 1000;
                        trafficRatioMatrix[i][j] = 1.0;
                        continue;
                    }

                    double liveTime = liveDurNode.asDouble();
                    double baseTime = baseDurNode.asDouble();
                    double dist = liveDistNode.asDouble();

                    if (liveTime <= 0 || baseTime <= 0 || dist <= 0) {
                        timeMatrix[i][j] = Double.MAX_VALUE / 1000;
                        distMatrix[i][j] = dist > 0 ? dist : Double.MAX_VALUE / 1000;
                        trafficRatioMatrix[i][j] = 1.0;
                        continue;
                    }

                    double ratio = liveTime / baseTime;

                    timeMatrix[i][j] = liveTime;
                    distMatrix[i][j] = dist;
                    trafficRatioMatrix[i][j] = Math.max(1.0, ratio);
                }
            }
        } catch (Exception e) {
            System.err.println("Matrix parsing failed (Mapbox live vs base). " + e.getMessage());
        }
    }

    private void buildOsrmMatrix(List<RouteNode> points, double trafficFactor) {
        int size = points.size();
        timeMatrix = new double[size][size];
        distMatrix = new double[size][size];
        trafficRatioMatrix = new double[size][size];

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
                trafficRatioMatrix[i][j] = safeFactor;
            }
        }
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
            System.err.println("Mapbox Directions geometry failed: " + e.getMessage());
        }
        return fullPath;
    }

    private Map<String, Object> buildSimpleFallback(List<RouteNode> allPoints, RouteRequest request) {
        List<RouteNode> ordered = new ArrayList<>(allPoints);

        double totalTime = 0, totalCost = 0, totalCo2 = 0;
        for (int i = 0; i < ordered.size() - 1; i++) {
            double segTime = timeMatrix[i][i + 1];
            double segDistKm = distMatrix[i][i + 1] / 1000.0;
            double segRatio = trafficRatioMatrix[i][i + 1];

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

    private double calculateAverageTrafficFactorForSolution(Solution solution, List<RouteNode> allOrders) {
        int[] permutation = ((Permutation) solution.getVariable(0)).toArray();

        List<Integer> pathIndices = new ArrayList<>();
        pathIndices.add(0); // start

        for (int index : permutation) {
            pathIndices.add(index + 1); // middle stops
        }

        pathIndices.add(allOrders.size() - 1); // end

        double totalRatio = 0.0;
        int segmentCount = 0;

        for (int i = 0; i < pathIndices.size() - 1; i++) {
            int fromIdx = pathIndices.get(i);
            int toIdx = pathIndices.get(i + 1);

            totalRatio += trafficRatioMatrix[fromIdx][toIdx];
            segmentCount++;
        }

        return segmentCount > 0 ? totalRatio / segmentCount : 1.0;
    }

    private double calculateDirectTrafficFactor() {
        return trafficRatioMatrix[0][1];
    }
}