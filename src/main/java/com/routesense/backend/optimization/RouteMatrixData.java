package com.routesense.backend.optimization;

public class RouteMatrixData {
    private final double[][] timeMatrix;
    private final double[][] distMatrix;
    private final double[][] trafficRatioMatrix;

    public RouteMatrixData(int size) {
        this.timeMatrix = new double[size][size];
        this.distMatrix = new double[size][size];
        this.trafficRatioMatrix = new double[size][size];
    }

    public double[][] getTimeMatrix() { return timeMatrix; }
    public double[][] getDistMatrix() { return distMatrix; }
    public double[][] getTrafficRatioMatrix() { return trafficRatioMatrix; }
}