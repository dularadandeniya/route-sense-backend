package com.routesense.backend;

import com.routesense.backend.model.DeliveryOrder;
import com.routesense.backend.optimization.RouteProblem;
import com.routesense.backend.repository.OrderRepository;
import com.routesense.backend.service.OsrmService;
import org.moeaframework.Executor;
import org.moeaframework.core.NondominatedPopulation;
import org.moeaframework.core.Solution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DataLoader implements CommandLineRunner {
    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OsrmService osrmService;

    @Override
    public void run(String... args) throws Exception {
        // 1. Seed Data (Same as before)
        if (orderRepository.count() == 0) {
            System.out.println(">> Seeding Dummy Data...");
            // ... (Keep your existing seeding code here) ...
            // If you deleted it, let me know, I can paste it back.
        }

        // 2. RUN OPTIMIZATION (The New Part)
        System.out.println(">> Starting NSGA-II Optimization...");

        List<DeliveryOrder> allOrders = orderRepository.findAll();

        if (!allOrders.isEmpty()) {
            // Setup the problem
            RouteProblem problem = new RouteProblem(allOrders, osrmService);

            // Run the Algorithm (100 iterations for speed)
            NondominatedPopulation result = new Executor()
                    .withProblem(problem)
                    .withAlgorithm("NSGAII")
                    .withMaxEvaluations(100)
                    .run();

            // Print the Results
            System.out.println(">> Optimization Finished! Found " + result.size() + " best routes.");
            for (Solution solution : result) {
                System.out.printf("   [Route Option] Time: %.0fs | Cost: $%.2f | CO2: %.3fkg \n",
                        solution.getObjective(0),
                        solution.getObjective(1),
                        solution.getObjective(2));
            }
        }
    }
}
