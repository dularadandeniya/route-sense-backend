package com.routesense.backend.service.impl;

import com.routesense.backend.dto.RouteRequest;
import com.routesense.backend.model.RouteNode;
import com.routesense.backend.optimization.RouteProblem;
import com.routesense.backend.service.ExplanationService;
import com.routesense.backend.service.OptimizationService;
import com.routesense.backend.service.OsrmService;
import com.routesense.backend.util.Emissions;
import org.moeaframework.Executor;
import org.moeaframework.core.NondominatedPopulation;
import org.moeaframework.core.Solution;
import org.moeaframework.core.variable.Permutation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    @Override
    public List<Map<String, Object>> findRoutesDynamic(RouteRequest request) {
        List<Map<String, Object>> results = new ArrayList<>();

        // MODE 1: Simple A to B (Direct)
        if (request.getStops() == null || request.getStops().isEmpty()) {
            List<Map<String, Object>> alternatives = osrmService.getRouteAlternatives(
                    request.getStartLat(), request.getStartLon(),
                    request.getEndLat(), request.getEndLon()
            );

            for (Map<String, Object> alt : alternatives) {
                double time = ((double) alt.get("duration")) * request.getTrafficFactor();
                double distKm = ((double) alt.get("distanceMeters")) / 1000.0;
                double fuelLiters = Emissions.calcFuelLiters(
                        distKm,
                        request.getWeightKg(),
                        request.getTrafficFactor(),
                        request.getVehicleType()
                );
                double cost = fuelLiters * Emissions.DIESEL_PRICE_LKR;
                double co2 = Emissions.calcCo2Kg(
                        distKm,
                        request.getWeightKg(),
                        request.getTrafficFactor(),
                        request.getVehicleType()
                );

                Map<String, Object> option = new HashMap<>();
                option.put("mode", "Direct Path");
                option.put("time_seconds", time);
                option.put("cost_currency", cost);
                option.put("co2_emissions", co2);
                option.put("explanation", explanationService.generateExplanation(time, cost, co2));

                // For direct mode, OSRM already gives us the full geometry
                option.put("route_sequence", decodePolyline((String) alt.get("geometry")));

                option.put("fuel_liters", fuelLiters);
                results.add(option);
            }
        }

        // MODE 2: Multi-Stop Optimization (NSGA-II)
        else {
            List<RouteNode> routePoints = new ArrayList<>();

            // 1. Start Point
            RouteNode start = new RouteNode();
            start.setLatitude(request.getStartLat());
            start.setLongitude(request.getStartLon());
            start.setCustomerName(request.getStartName());
            routePoints.add(start);

            // 2. Middle Stops
            for (RouteRequest.Waypoint wp : request.getStops()) {
                RouteNode stop = new RouteNode();
                stop.setLatitude(wp.getLatitude());
                stop.setLongitude(wp.getLongitude());
                routePoints.add(stop);
            }

            // 3. End Point
            RouteNode end = new RouteNode();
            end.setLatitude(request.getEndLat());
            end.setLongitude(request.getEndLon());
            end.setCustomerName(request.getEndName());
            routePoints.add(end);

            // Run NSGA-II Algorithm
            RouteProblem problem = new RouteProblem(
                    routePoints,
                    osrmService,
                    request.getTrafficFactor(),
                    request.getWeightKg(),
                    request.getVehicleType()
            );

            NondominatedPopulation population = new Executor()
                    .withProblem(problem)
                    .withAlgorithm("NSGAII")
                    .withMaxEvaluations(500)
                    .run();

            List<String> foundPermutations = new ArrayList<>();

            // Process Optimal Solutions
            for (Solution solution : population) {
                String permString = solution.getVariable(0).toString();
                if (foundPermutations.contains(permString)) continue;
                foundPermutations.add(permString);

                Map<String, Object> routeOption = new HashMap<>();
                double time = solution.getObjective(0);
                double cost = solution.getObjective(1);
                double co2 = solution.getObjective(2);

                routeOption.put("mode", "Recommended (Optimal)");
                routeOption.put("time_seconds", time);
                routeOption.put("cost_currency", cost);
                routeOption.put("co2_emissions", co2);
                routeOption.put("explanation", explanationService.generateExplanation(time, cost, co2));

                // NEW: Fetch full curved geometry instead of just points
                routeOption.put("route_sequence", fetchFullRouteGeometry(solution, routePoints));

                double fuelLiters = cost / Emissions.DIESEL_PRICE_LKR;
                routeOption.put("fuel_liters", fuelLiters);

                results.add(routeOption);
            }

            // Generate Alternatives if needed
            int maxAttempts = 50;
            int attempts = 0;

            if (!results.isEmpty()) {
                Map<String, Object> bestRoute = results.get(0);
                double bestTime = (double) bestRoute.get("time_seconds");
                double bestCost = (double) bestRoute.get("cost_currency");
                double bestCO2 = (double) bestRoute.get("co2_emissions");

                while (results.size() < 3 && attempts < maxAttempts) {
                    attempts++;
                    Solution randomSol = problem.newSolution();
                    String permString = randomSol.getVariable(0).toString();

                    if (foundPermutations.contains(permString)) continue;

                    problem.evaluate(randomSol);
                    foundPermutations.add(permString);

                    Map<String, Object> routeOption = new HashMap<>();
                    double time = randomSol.getObjective(0);
                    double cost = randomSol.getObjective(1);
                    double co2 = randomSol.getObjective(2);

                    routeOption.put("mode", "Alternative Route");
                    routeOption.put("time_seconds", time);
                    routeOption.put("cost_currency", cost);
                    routeOption.put("co2_emissions", co2);

                    double timeDiff = time - bestTime;
                    double costDiff = cost - bestCost;
                    double co2Diff = co2 - bestCO2;

                    routeOption.put("explanation", explanationService.generateComparisonExplanation(timeDiff, costDiff, co2Diff));

                    // NEW: Fetch full geometry for alternatives too
                    routeOption.put("route_sequence", fetchFullRouteGeometry(randomSol, routePoints));

                    results.add(routeOption);
                }
            }
        }

        return results;
    }

    /**
     * Converts the optimized stop order into a full list of GPS coordinates
     * representing the actual winding road path.
     */
    private List<Map<String, Object>> fetchFullRouteGeometry(Solution solution, List<RouteNode> allOrders) {
        List<Map<String, Object>> fullPath = new ArrayList<>();
        int[] permutation = ((Permutation) solution.getVariable(0)).toArray();

        // 1. Construct the ordered list of Stops
        List<RouteNode> orderedStops = new ArrayList<>();
        orderedStops.add(allOrders.get(0)); // Start
        for (int index : permutation) {
            orderedStops.add(allOrders.get(index + 1)); // Middle stops
        }
        if (allOrders.size() > 1) {
            orderedStops.add(allOrders.get(allOrders.size() - 1)); // End
        }

        // 2. Loop through pairs (A->B, B->C) and fetch geometry
        for (int i = 0; i < orderedStops.size() - 1; i++) {
            RouteNode from = orderedStops.get(i);
            RouteNode to = orderedStops.get(i + 1);

            // Call OSRM for the specific road shape between these two points
            Map<String, Object> segment = osrmService.getRoute(
                    from.getLatitude(), from.getLongitude(),
                    to.getLatitude(), to.getLongitude()
            );

            if (segment != null && segment.containsKey("geometry")) {
                String polyline = (String) segment.get("geometry");
                fullPath.addAll(decodePolyline(polyline));
            }
        }
        return fullPath;
    }

    /**
     * Helper: Decodes OSRM's compressed string into Lat/Lon maps.
     * (Assuming standard Polyline encoding precision 1e5)
     */
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