package com.routesense.backend.service;

import org.springframework.stereotype.Service;

@Service
public class ExplanationService {
    public String generateExplanation(double time, double cost, double co2) {
        // Rule 1: The "Green" Win
        if (co2 < 0.13 && time > 128.0) {
            return String.format("This route reduces CO2 emissions by %.1f%% compared to the standard, though it adds a slight delay.",
                    (0.15 - co2) * 1000);
        }

        // Rule 2: The "Fast" Win
        if (time <= 128.0) {
            return String.format("This is the fastest available route, saving %.0f seconds compared to alternatives.",
                    130.0 - time);
        }

        // Rule 3: The "Balanced" Trade-off
        return "This route offers a balanced trade-off between cost and delivery time.";
    }

    public String generateComparisonExplanation(double timeDiff, double costDiff, double co2Diff) {
        StringBuilder sb = new StringBuilder();

        // 1. Analyze Time
        if (timeDiff > 0) {
            long min = Math.round(timeDiff / 60);
            sb.append("Takes ").append(min).append(" mins longer");
        } else {
            long min = Math.round(Math.abs(timeDiff) / 60);
            sb.append("Saves ").append(min).append(" mins");
        }

        // 2. Analyze CO2 (The Trade-off)
        if (co2Diff < 0) {
            sb.append(", but reduces emissions by ").append(String.format("%.2f", Math.abs(co2Diff))).append(" kg.");
        } else if (co2Diff > 0) {
            sb.append(", and produces ").append(String.format("%.2f", co2Diff)).append(" kg more CO2.");
        } else {
            sb.append(".");
        }

        return sb.toString();
    }
}
