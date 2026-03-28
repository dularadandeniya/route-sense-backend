package com.routesense.backend.controller;

import com.routesense.backend.dto.ScheduleTripRequest;
import com.routesense.backend.dto.ScheduledTripResponse;
import com.routesense.backend.service.ScheduledTripService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/schedules")
@CrossOrigin(origins = "http://localhost:5173")
public class ScheduleController {

    @Autowired
    private ScheduledTripService scheduledTripService;

    @PostMapping
    public ScheduledTripResponse createSchedule(@RequestBody ScheduleTripRequest request) {
        return scheduledTripService.createSchedule(request);
    }

    @GetMapping
    public List<ScheduledTripResponse> getAllSchedules() {
        return scheduledTripService.getAllSchedules();
    }

    @PostMapping("/{id}/optimize")
    public List<Map<String, Object>> optimizeScheduledTrip(@PathVariable Long id) {
        return scheduledTripService.optimizeScheduledTrip(id);
    }
}