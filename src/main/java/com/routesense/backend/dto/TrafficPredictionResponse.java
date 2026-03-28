package com.routesense.backend.dto;

import lombok.Data;

@Data
public class TrafficPredictionResponse {
    private double trafficFactor;
    private String modelVersion;
}