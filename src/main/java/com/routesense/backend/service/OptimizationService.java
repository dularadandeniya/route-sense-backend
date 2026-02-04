package com.routesense.backend.service;

import com.routesense.backend.dto.RouteRequest;

import java.util.List;
import java.util.Map;


public interface OptimizationService {
    List<Map<String, Object>> findRoutesDynamic(RouteRequest request);
}
