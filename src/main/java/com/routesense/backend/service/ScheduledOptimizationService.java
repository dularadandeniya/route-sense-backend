package com.routesense.backend.service;

import com.routesense.backend.entity.ScheduledTrip;

import java.util.List;
import java.util.Map;

public interface ScheduledOptimizationService {
    List<Map<String, Object>> optimizeScheduledTrip(ScheduledTrip trip);
}