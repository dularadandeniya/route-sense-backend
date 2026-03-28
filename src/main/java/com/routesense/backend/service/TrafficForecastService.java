package com.routesense.backend.service;

import com.routesense.backend.dto.TrafficPredictionRequest;

public interface TrafficForecastService {
    double predictTrafficFactor(TrafficPredictionRequest request);
}