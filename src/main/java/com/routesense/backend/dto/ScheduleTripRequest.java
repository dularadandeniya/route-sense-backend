package com.routesense.backend.dto;

import com.routesense.backend.dto.RouteRequest.VehicleType;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ScheduleTripRequest {

    private String tripName;

    private String startName;
    private double startLat;
    private double startLon;

    private String endName;
    private double endLat;
    private double endLon;

    private List<StopDto> stops;

    private LocalDateTime departureTime;

    private double weightKg;
    private VehicleType vehicleType;

    @Data
    public static class StopDto {
        private String name;
        private double latitude;
        private double longitude;
    }

    public VehicleType getVehicleType() {
        return vehicleType == null ? VehicleType.MEDIUM : vehicleType;
    }
}