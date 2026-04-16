package com.routesense.backend.controller;

import com.routesense.backend.dto.ScheduleTripRequest;
import com.routesense.backend.dto.ScheduledTripResponse;
import com.routesense.backend.service.ScheduledTripService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/schedules")
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

    @PostMapping("/bulk")
    public ResponseEntity<?> bulkCreateSchedules(@RequestBody List<ScheduleTripRequest> requests) {
        List<ScheduledTripResponse> results = new ArrayList<>();
        for (ScheduleTripRequest req : requests) {
            try {
                ScheduledTripResponse resp = scheduledTripService.createSchedule(req);
                results.add(resp);
            } catch (Exception e) {
            }
        }
        return ResponseEntity.ok(results);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getScheduleById(@PathVariable Long id) {
        return ResponseEntity.ok(scheduledTripService.getScheduleById(id));
    }


}