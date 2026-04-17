package com.routesense.backend.util;

import com.routesense.backend.entity.RouteNode;
import com.routesense.backend.optimization.RouteMatrixData;
import org.moeaframework.core.NondominatedPopulation;
import org.moeaframework.core.Solution;
import org.moeaframework.core.variable.Permutation;

import java.util.ArrayList;
import java.util.List;


public final class RouteUtils {

    private RouteUtils() {}

    public static String buildCoordinateString(List<RouteNode> points) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            sb.append(points.get(i).getLongitude()).append(",").append(points.get(i).getLatitude());
            if (i < points.size() - 1) sb.append(";");
        }
        return sb.toString();
    }

    public static List<RouteNode> buildOrderedStops(Solution solution, List<RouteNode> allOrders) {
        int[] permutation = ((Permutation) solution.getVariable(0)).toArray();
        List<RouteNode> orderedStops = new ArrayList<>();
        orderedStops.add(allOrders.get(0));
        for (int index : permutation) {
            orderedStops.add(allOrders.get(index + 1));
        }
        orderedStops.add(allOrders.get(allOrders.size() - 1));
        return orderedStops;
    }

    public static List<String> extractStopOrderNames(List<RouteNode> orderedStops) {
        List<String> names = new ArrayList<>();
        for (RouteNode n : orderedStops) names.add(n.getName());
        return names;
    }

    public static void addIfUnique(List<Solution> list, Solution candidate) {
        if (candidate == null) return;
        String perm = candidate.getVariable(0).toString();
        for (Solution s : list) {
            if (s.getVariable(0).toString().equals(perm)) return;
        }
        list.add(candidate);
    }

    public static Solution pickKneeFastGreen(NondominatedPopulation pop) {
        double minT = Double.MAX_VALUE, maxT = -Double.MAX_VALUE;
        double minC = Double.MAX_VALUE, maxC = -Double.MAX_VALUE;

        for (Solution s : pop) {
            minT = Math.min(minT, s.getObjective(0));
            maxT = Math.max(maxT, s.getObjective(0));
            minC = Math.min(minC, s.getObjective(2));
            maxC = Math.max(maxC, s.getObjective(2));
        }

        double tRange = Math.max(1e-9, maxT - minT);
        double cRange = Math.max(1e-9, maxC - minC);

        Solution best = null;
        double bestScore = Double.MAX_VALUE;

        for (Solution s : pop) {
            double tNorm = (s.getObjective(0) - minT) / tRange;
            double cNorm = (s.getObjective(2) - minC) / cRange;
            double score = tNorm + cNorm;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    public static double calculateAverageTrafficFactor(Solution solution, List<RouteNode> allOrders,
                                                       RouteMatrixData matrix) {
        int[] permutation = ((Permutation) solution.getVariable(0)).toArray();

        List<Integer> pathIndices = new ArrayList<>();
        pathIndices.add(0);
        for (int index : permutation) {
            pathIndices.add(index + 1);
        }
        pathIndices.add(allOrders.size() - 1);

        double total = 0.0;
        int count = 0;
        for (int i = 0; i < pathIndices.size() - 1; i++) {
            total += matrix.getTrafficRatioMatrix()[pathIndices.get(i)][pathIndices.get(i + 1)];
            count++;
        }
        return count == 0 ? 1.0 : total / count;
    }
}