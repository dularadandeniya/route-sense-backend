package com.routesense.backend.service;

import org.springframework.stereotype.Service;

@Service
public class ExplanationService {

    // Used for the optimal or direct route
    public String generateExplanation(double time, double cost, double co2, double baseTime, double baseCo2) {
        if (co2 < baseCo2 && time > baseTime) {
            double saved = ((baseCo2 - co2) / baseCo2) * 100;
            return String.format("Reduces CO2 by %.1f%%. Adds a slight delay.", saved);
        }

        if (time <= baseTime) {
            return "This is the fastest available route.";
        }

        long mins = Math.round(time / 60.0);
        return String.format("Balanced route: %d mins, LKR %.2f, %.2f kg CO2.", mins, cost, co2);
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