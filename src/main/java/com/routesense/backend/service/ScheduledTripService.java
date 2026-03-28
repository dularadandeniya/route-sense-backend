package com.routesense.backend.service;

import com.routesense.backend.dto.ScheduleTripRequest;
import com.routesense.backend.dto.ScheduledTripResponse;

import java.util.List;
import java.util.Map;

public interface ScheduledTripService {
    ScheduledTripResponse createSchedule(ScheduleTripRequest request);
    List<ScheduledTripResponse> getAllSchedules();
    List<Map<String, Object>> optimizeScheduledTrip(Long id);
}