package com.routesense.backend.service.impl;

import com.routesense.backend.dto.RouteRequest;
import com.routesense.backend.model.DeliveryOrder;
import com.routesense.backend.optimization.RouteProblem;
import com.routesense.backend.service.ExplanationService;
import com.routesense.backend.service.OptimizationService;
import com.routesense.backend.service.OsrmService;
import com.routesense.backend.util.Emissions;
import org.moeaframework.Executor;
import org.moeaframework.core.NondominatedPopulation;
import org.moeaframework.core.Solution;
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

        // mode 1: A to B
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

                double payloadKg = request.getWeightKg();
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
                option.put("geometry", alt.get("geometry"));
                option.put("fuel_liters", fuelLiters);

                results.add(option);
            }
        }

        // mode 2: Multi-stop optimization
        else {
            List<DeliveryOrder> routePoints = new ArrayList<>();


            // Start Point
            DeliveryOrder start = new DeliveryOrder();
            start.setLatitude(request.getStartLat());
            start.setLongitude(request.getStartLon());
            start.setCustomerName(request.getStartName());
            routePoints.add(start);

            // Middle Stops
            for (RouteRequest.Waypoint wp : request.getStops()) {
                DeliveryOrder stop = new DeliveryOrder();
                stop.setLatitude(wp.getLatitude());
                stop.setLongitude(wp.getLongitude());
                stop.setCustomerName(wp.getName());
                routePoints.add(stop);
            }

            // End Point
            DeliveryOrder end = new DeliveryOrder();
            end.setLatitude(request.getEndLat());
            end.setLongitude(request.getEndLon());
            end.setCustomerName(request.getEndName());
            routePoints.add(end);


            // 2. Run NSGA-II Algorithm
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

            for (Solution solution : population) {
                Map<String, Object> routeOption = new HashMap<>();
                double time = solution.getObjective(0);
                double cost = solution.getObjective(1);
                double co2 = solution.getObjective(2);

                foundPermutations.add(solution.getVariable(0).toString());

                routeOption.put("mode", "Recommended (Optimal)");
                routeOption.put("time_seconds", time);
                routeOption.put("cost_currency", cost);
                routeOption.put("co2_emissions", co2);
                routeOption.put("explanation", explanationService.generateExplanation(time, cost, co2));

                routeOption.put("route_sequence", getOrderedPoints(solution, routePoints));
                double fuelLiters = cost / Emissions.DIESEL_PRICE_LKR;
                routeOption.put("fuel_liters", fuelLiters);


                results.add(routeOption);
            }

            int maxAttempts = 50;
            int attempts = 0;

            Map<String, Object> bestRoute = results.get(0);
            double bestTime = (double) bestRoute.get("time_seconds");
            double bestCost = (double) bestRoute.get("cost_currency");
            double bestCO2 = (double) bestRoute.get("co2_emissions");

            while (results.size() < 3 && attempts < maxAttempts) {
                attempts++;

                Solution randomSol = problem.newSolution();
                String permString = randomSol.getVariable(0).toString();

                if (foundPermutations.contains(permString)) {
                    continue;
                }

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

                double fuelLiters = cost / Emissions.DIESEL_PRICE_LKR;
                routeOption.put("fuel_liters", fuelLiters);

                double timeDiff = time - bestTime;
                double costDiff = cost - bestCost;
                double co2Diff = co2 - bestCO2;

                String comparisonText = explanationService.generateComparisonExplanation(timeDiff, costDiff, co2Diff);
                routeOption.put("explanation", comparisonText);

                routeOption.put("route_sequence", getOrderedPoints(randomSol, routePoints));

                results.add(routeOption);
            }
        }

        return results;
    }

    private List<Map<String, Object>> getOrderedPoints(Solution solution, List<DeliveryOrder> allOrders) {
        List<Map<String, Object>> orderedList = new ArrayList<>();

        int[] permutation = ((org.moeaframework.core.variable.Permutation) solution.getVariable(0)).toArray();

        orderedList.add(orderToMap(allOrders.get(0)));

        for (int index : permutation) {
            orderedList.add(orderToMap(allOrders.get(index + 1)));
        }

        if (allOrders.size() > 1) {
            orderedList.add(orderToMap(allOrders.get(allOrders.size() - 1)));
        }

        return orderedList;
    }

    private Map<String, Object> orderToMap(DeliveryOrder order) {
        Map<String, Object> map = new HashMap<>();
        map.put("name", order.getCustomerName());
        map.put("lat", order.getLatitude());
        map.put("lon", order.getLongitude());
        return map;
    }

}