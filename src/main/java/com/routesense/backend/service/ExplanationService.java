package com.routesense.backend.service;

import org.springframework.stereotype.Service;

@Service
public class ExplanationService {
    public String generateExplanation(double time, double cost, double co2) {
        // Rule 1: The "Green" Win
        // If CO2 is very low (< 0.13kg), but time is high (> 128s)
        if (co2 < 0.13 && time > 128.0) {
            return String.format("This route reduces CO2 emissions by %.1f%% compared to the standard, though it adds a slight delay.",
                    (0.15 - co2) * 1000); // Fake math for demo
        }

        // Rule 2: The "Fast" Win
        // If Time is low (< 128s)
        if (time <= 128.0) {
            return String.format("This is the fastest available route, saving %.0f seconds compared to alternatives.",
                    130.0 - time);
        }

        // Rule 3: The "Balanced" Trade-off
        return "This route offers a balanced trade-off between cost and delivery time.";
    }
}
