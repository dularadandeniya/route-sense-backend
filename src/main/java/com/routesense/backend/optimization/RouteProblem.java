package com.routesense.backend.optimization;

import com.routesense.backend.dto.RouteRequest;
import com.routesense.backend.entity.RouteNode;
import com.routesense.backend.util.Emissions; // Ensure this matches your final naming (Emissions or EmissionsService)
import org.moeaframework.core.Solution;
import org.moeaframework.core.variable.Permutation;
import org.moeaframework.problem.AbstractProblem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RouteProblem extends AbstractProblem {

    private final List<RouteNode> orders;
    private final double[][] timeMatrix;
    private final double[][] distMatrix;
    private final double[][] trafficRatioMatrix; // New: Segment-specific traffic ratios
    private final double payloadKg;
    private final RouteRequest.VehicleType vehicleType;
    private final Emissions emissions; // Injected service for math

    public RouteProblem(List<RouteNode> orders,
                        double[][] timeMatrix,
                        double[][] distMatrix,
                        double[][] trafficRatioMatrix,
                        double payloadKg,
                        RouteRequest.VehicleType vehicleType,
                        Emissions emissions) {

        super(1, 3);
        this.orders = orders;
        this.timeMatrix = timeMatrix;
        this.distMatrix = distMatrix;
        this.trafficRatioMatrix = trafficRatioMatrix;
        this.payloadKg = Math.max(0.0, payloadKg);
        this.vehicleType = vehicleType == null ? RouteRequest.VehicleType.MEDIUM : vehicleType;
        this.emissions = emissions;
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

        List<Integer> pathIndices = new ArrayList<>();
        pathIndices.add(0); // Start node

        for (int index : permutation) {
            pathIndices.add(index + 1); // Middle stops
        }

        if (orders.size() > 1) {
            pathIndices.add(orders.size() - 1); // End node
        }

        double totalTime = 0.0;
        double totalCost = 0.0;
        double totalCO2 = 0.0;

        // Loop through segments to apply segment-specific traffic data
        for (int i = 0; i < pathIndices.size() - 1; i++) {
            int fromIdx = pathIndices.get(i);
            int toIdx = pathIndices.get(i + 1);

            double segTime = timeMatrix[fromIdx][toIdx];
            double segDistMeters = distMatrix[fromIdx][toIdx];
            double segTrafficRatio = trafficRatioMatrix[fromIdx][toIdx]; // Live ratio for this road

            // Penalize invalid paths
            if (segTime <= 0) segTime = Double.MAX_VALUE / 1000;

            double segDistKm = segDistMeters / 1000.0;

            // Objective 1: Time
            totalTime += segTime;

            // Objective 2 & 3: Cost and CO2 using the actual segment traffic ratio
            double segFuel = emissions.calcFuelLiters(segDistKm, payloadKg, segTrafficRatio, vehicleType);
            totalCost += (segFuel * emissions.getDieselPrice());
            totalCO2 += emissions.calcCo2Kg(segDistKm, payloadKg, segTrafficRatio, vehicleType);
        }

        solution.setObjective(0, totalTime);
        solution.setObjective(1, totalCost);
        solution.setObjective(2, totalCO2);
    }
}