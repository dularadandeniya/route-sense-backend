package com.routesense.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrafficPredictionRequest {
    private String sourceName;
    private String destinationName;
    private double sourceLat;
    private double sourceLon;
    private double destinationLat;
    private double destinationLon;
    private double distanceKm;
    private LocalDateTime departureTime;
}