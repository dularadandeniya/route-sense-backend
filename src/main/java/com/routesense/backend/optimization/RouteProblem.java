package com.routesense.backend.optimization;

import com.routesense.backend.dto.RouteRequest;
import com.routesense.backend.model.DeliveryOrder;
import com.routesense.backend.service.OsrmService;
import com.routesense.backend.util.Emissions;
import org.moeaframework.core.Solution;
import org.moeaframework.core.variable.Permutation;
import org.moeaframework.problem.AbstractProblem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RouteProblem extends AbstractProblem {

    private final List<DeliveryOrder> orders;
    private final OsrmService osrmService;
    private final double trafficFactor;
    private final double payloadKg;
    private final RouteRequest.VehicleType vehicleType;

    public RouteProblem(List<DeliveryOrder> orders,
                        OsrmService osrmService,
                        double trafficFactor,
                        double payloadKg,
                        RouteRequest.VehicleType vehicleType) {

        super(1, 3);
        this.orders = orders;
        this.osrmService = osrmService;
        this.trafficFactor = trafficFactor <= 0 ? 1.0 : trafficFactor;
        this.payloadKg = Math.max(0.0, payloadKg);
        this.vehicleType = vehicleType == null ? RouteRequest.VehicleType.MEDIUM : vehicleType;
    }

    @Override
    public Solution newSolution() {
        Solution solution = new Solution(1, 3);
        int middleStopsCount = Math.max(0, orders.size() - 2);

        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < middleStopsCount; i++) indices.add(i);

        Collections.shuffle(indices);

        int[] shuffledArray = indices.stream().mapToInt(i -> i).toArray();
        solution.setVariable(0, new Permutation(shuffledArray));

        return solution;
    }

    @Override
    public void evaluate(Solution solution) {
        int[] permutation = ((Permutation) solution.getVariable(0)).toArray();

        List<DeliveryOrder> path = new ArrayList<>();

        path.add(orders.get(0)); // start

        for (int index : permutation) {
            path.add(orders.get(index + 1));
        }

        if (orders.size() > 1) {
            path.add(orders.get(orders.size() - 1)); // end
        }

        double totalTime = 0.0;
        double totalDistanceKm = 0.0;

        for (int i = 0; i < path.size() - 1; i++) {
            DeliveryOrder from = path.get(i);
            DeliveryOrder to = path.get(i + 1);

            OsrmService.RouteMetrics m = osrmService.getRouteMetrics(
                    from.getLatitude(), from.getLongitude(),
                    to.getLatitude(), to.getLongitude(),
                    trafficFactor
            );

            double segTime = m.durationSeconds();
            double segDistMeters = m.distanceMeters();

            // Penalize failures
            if (segTime < 0) segTime = 10000;
            if (segDistMeters < 0) segDistMeters = 1_000_000;

            totalTime += segTime;
            totalDistanceKm += (segDistMeters / 1000.0);
        }

        double objectiveTime = totalTime;

        double fuelLiters = Emissions.calcFuelLiters(totalDistanceKm, payloadKg, trafficFactor, vehicleType);
        double objectiveCost = fuelLiters * Emissions.DIESEL_PRICE_LKR;

        double objectiveCO2 = Emissions.calcCo2Kg(totalDistanceKm, payloadKg, trafficFactor, vehicleType);

        solution.setObjective(0, objectiveTime);
        solution.setObjective(1, objectiveCost);
        solution.setObjective(2, objectiveCO2);
    }
}
