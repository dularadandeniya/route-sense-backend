package com.routesense.backend.optimization;

import com.routesense.backend.model.DeliveryOrder;
import com.routesense.backend.service.OsrmService;
import org.moeaframework.core.Solution;
import org.moeaframework.core.variable.Permutation;
import org.moeaframework.problem.AbstractProblem;

import java.util.List;

public class RouteProblem extends AbstractProblem {
    private final List<DeliveryOrder> orders;
    private final OsrmService osrmService;

    // Constructor: We pass the list of orders and the map service so the algorithm can use them
    public RouteProblem(List<DeliveryOrder> orders, OsrmService osrmService) {
        // 1 Variable (The Route Order), 3 Objectives (Time, Cost, CO2)
        super(1, 3);
        this.orders = orders;
        this.osrmService = osrmService;
    }

    @Override
    public void evaluate(Solution solution) {
        // 1. Get the suggested order of stops (The "Permutation")
        // E.g., The algorithm says: "Visit Order #2, then Order #5, then Order #1"
        int[] permutation = ((Permutation) solution.getVariable(0)).toArray();

        double totalTime = 0.0;
        double totalDistance = 0.0;

        // 2. Calculate Total Time & Distance by looping through the route
        // (We start from the first customer in the list for now)
        for (int i = 0; i < permutation.length - 1; i++) {
            DeliveryOrder from = orders.get(permutation[i]);
            DeliveryOrder to = orders.get(permutation[i + 1]);

            // Ask OSRM for the time between these two stops
            double timeSegment = osrmService.getDuration(
                    from.getLatitude(), from.getLongitude(),
                    to.getLatitude(), to.getLongitude()
            );

            totalTime += timeSegment;
            // Assume average speed of 30km/h (8.33 m/s) to guess distance for now
            totalDistance += (timeSegment * 8.33) / 1000.0; // in km
        }

        // 3. Calculate Objectives
        // Objective 1: Minimize Time (Seconds)
        double objectiveTime = totalTime;

        // Objective 2: Minimize Cost (Simplistic Formula: $50 base + $10 per km)
        double objectiveCost = 50.0 + (totalDistance * 10.0);

        // Objective 3: Minimize CO2 (Simplistic Formula: 0.12kg per km)
        double objectiveCO2 = totalDistance * 0.12;

        // 4. Save the scores back to the Solution
        solution.setObjective(0, objectiveTime);
        solution.setObjective(1, objectiveCost);
        solution.setObjective(2, objectiveCO2);
    }

    @Override
    public Solution newSolution() {
        // Create a new random route permutation (size = number of orders)
        Solution solution = new Solution(1, 3);
        solution.setVariable(0, new Permutation(orders.size()));
        return solution;
    }
}
