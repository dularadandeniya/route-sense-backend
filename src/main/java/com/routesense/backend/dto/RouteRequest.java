package com.routesense.backend.dto;

import lombok.Data;

import java.util.List;

@Data
public class RouteRequest {
    private String startName;
    private double startLat;
    private double startLon;

    private String endName;
    private double endLat;
    private double endLon;

    private List<Waypoint> stops;

    private double weightKg;
    private int readyTime;
    private int dueTime;

    private double trafficFactor = 1.0;

    @Data
    public static class Waypoint {
        private String name;
        private double latitude;
        private double longitude;
    }
    public enum VehicleType { LIGHT, MEDIUM, HEAVY }

    private VehicleType vehicleType = VehicleType.MEDIUM;

    public VehicleType getVehicleType() {
        return vehicleType == null ? VehicleType.MEDIUM : vehicleType;
    }

    public double getTrafficFactor() {
        if (trafficFactor <= 0) return 1.0;
        return trafficFactor;
    }

    public String getStartName() {
        return (startName == null || startName.isEmpty()) ? "Start Location" : startName;
    }

    public String getEndName() {
        return (endName == null || endName.isEmpty()) ? "Final Destination" : endName;
    }


}
