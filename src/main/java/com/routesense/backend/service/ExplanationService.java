package com.routesense.backend.service;

import org.springframework.stereotype.Service;

@Service
public class ExplanationService {

    public String generateExplanation(double time, double cost, double co2, double baseTime, double baseCo2) {

        // If greener than base but slower
        if (co2 < baseCo2 && time > baseTime) {
            double savedPct = ((baseCo2 - co2) / baseCo2) * 100.0;
            long extraMin = Math.round((time - baseTime) / 60.0);
            return String.format("Reduces CO2 by %.1f%% with ~%d min time trade-off.", savedPct, Math.max(1, extraMin));
        }

        // If this is the fastest (or tied fastest)
        if (time <= baseTime) {
            return "This is the fastest available route.";
        }

        return "Balanced route considering time, cost, and emissions.";
    }

    public String generateComparisonExplanation(double timeDiff, double costDiff, double co2Diff) {
        StringBuilder sb = new StringBuilder();

        // Time
        long mins = Math.round(Math.abs(timeDiff) / 60.0);
        if (timeDiff > 0) sb.append("Takes ").append(mins).append(" mins longer");
        else sb.append("Saves ").append(mins).append(" mins");

        // Cost
        long costAbs = Math.round(Math.abs(costDiff));
        if (costDiff > 0) sb.append(", costs ~Rs ").append(costAbs).append(" more");
        else if (costDiff < 0) sb.append(", saves ~Rs ").append(costAbs);

        // CO2
        if (co2Diff < 0) {
            sb.append(", and reduces CO2 by ").append(String.format("%.2f", Math.abs(co2Diff))).append(" kg.");
        } else if (co2Diff > 0) {
            sb.append(", but emits ").append(String.format("%.2f", co2Diff)).append(" kg more CO2.");
        } else {
            sb.append(".");
        }

        return sb.toString();
    }
}