package com.routesense.backend.service;

import com.routesense.backend.model.DeliveryOrder;
import com.routesense.backend.optimization.RouteProblem;
import com.routesense.backend.repository.OrderRepository;
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
public class OptimizationService {
    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OsrmService osrmService;

    @Autowired
    private ExplanationService explanationService;

    public List<Map<String, Object>> findOptimalRoutes() {
        List<DeliveryOrder> allOrders = orderRepository.findAll();
        List<Map<String, Object>> results = new ArrayList<>();

        if (allOrders.isEmpty()) return results;

        // 1. Setup the Problem
        RouteProblem problem = new RouteProblem(allOrders, osrmService);

        // 2. Run NSGA-II
        NondominatedPopulation population = new Executor()
                .withProblem(problem)
                .withAlgorithm("NSGAII")
                .withMaxEvaluations(500) // Increased for better results
                .run();

        // 3. Convert Results to JSON-friendly format
        for (Solution solution : population) {
            Map<String, Object> routeOption = new HashMap<>();
            routeOption.put("time_seconds", solution.getObjective(0));
            routeOption.put("cost_currency", solution.getObjective(1));
            routeOption.put("co2_emissions", solution.getObjective(2));

            // Add a simple explanation (Rule-Based XAI)
            double time = solution.getObjective(0);
            double cost = solution.getObjective(1);
            double co2 = solution.getObjective(2);

            routeOption.put("time_seconds", time);
            routeOption.put("cost_currency", cost);
            routeOption.put("co2_emissions", co2);

            // NEW: Call the dedicated XAI Service
            String explanation = explanationService.generateExplanation(time, cost, co2);
            routeOption.put("explanation", explanation);

            results.add(routeOption);
        }

        return results;
    }
}
