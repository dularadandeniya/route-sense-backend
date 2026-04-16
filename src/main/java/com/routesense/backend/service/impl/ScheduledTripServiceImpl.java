package com.routesense.backend.service.impl;

import com.routesense.backend.dto.ScheduleTripRequest;
import com.routesense.backend.dto.ScheduledTripResponse;
import com.routesense.backend.entity.ScheduledTrip;
import com.routesense.backend.entity.ScheduledTripStop;
import com.routesense.backend.repository.ScheduledTripRepository;
import com.routesense.backend.service.ScheduledOptimizationService;
import com.routesense.backend.service.ScheduledTripService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ScheduledTripServiceImpl implements ScheduledTripService {

    @Autowired
    private ScheduledTripRepository scheduledTripRepository;

    @Autowired
    private ScheduledOptimizationService scheduledOptimizationService;

    @Override
    public ScheduledTripResponse createSchedule(ScheduleTripRequest request) {
        ScheduledTrip trip = new ScheduledTrip();
        trip.setTripName(request.getTripName());
        trip.setStartName(request.getStartName());
        trip.setStartLat(request.getStartLat());
        trip.setStartLon(request.getStartLon());
        trip.setEndName(request.getEndName());
        trip.setEndLat(request.getEndLat());
        trip.setEndLon(request.getEndLon());
        trip.setDepartureTime(request.getDepartureTime());
        trip.setVehicleType(request.getVehicleType().name());
        trip.setPayloadKg(request.getWeightKg());
        trip.setStatus("DRAFT");

        if (request.getStops() != null) {
            int seq = 1;
            for (ScheduleTripRequest.StopDto s : request.getStops()) {
                ScheduledTripStop stop = new ScheduledTripStop();
                stop.setScheduledTrip(trip);
                stop.setSeq(seq++);
                stop.setStopName(s.getName());
                stop.setStopLat(s.getLatitude());
                stop.setStopLon(s.getLongitude());
                trip.getStops().add(stop);
            }
        }

        ScheduledTrip saved = scheduledTripRepository.save(trip);

        return new ScheduledTripResponse(
                saved.getId(),
                saved.getTripName(),
                saved.getDepartureTime(),
                saved.getStatus()
        );
    }

    @Override
    public List<ScheduledTripResponse> getAllSchedules() {
        return scheduledTripRepository.findAll()
                .stream()
                .map(t -> new ScheduledTripResponse(
                        t.getId(),
                        t.getTripName(),
                        t.getDepartureTime(),
                        t.getStatus()
                ))
                .toList();
    }

    @Override
    public List<Map<String, Object>> optimizeScheduledTrip(Long id) {
        ScheduledTrip trip = scheduledTripRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Scheduled trip not found"));

        return scheduledOptimizationService.optimizeScheduledTrip(trip);
    }

    @Override
    public Map<String, Object> getScheduleById(Long id) {
        ScheduledTrip trip = scheduledTripRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Trip not found: " + id));

        Map<String, Object> result = new HashMap<>();
        result.put("id",            trip.getId());
        result.put("tripName",      trip.getTripName());
        result.put("startName",     trip.getStartName());
        result.put("startLat",      trip.getStartLat());
        result.put("startLon",      trip.getStartLon());
        result.put("endName",       trip.getEndName());
        result.put("endLat",        trip.getEndLat());
        result.put("endLon",        trip.getEndLon());
        result.put("departureTime", trip.getDepartureTime().toString());
        result.put("vehicleType",   trip.getVehicleType());
        result.put("weightKg",      trip.getPayloadKg());
        result.put("status",        trip.getStatus());

        List<Map<String, Object>> stops = new ArrayList<>();
        if (trip.getStops() != null) {
            trip.getStops().stream()
                    .sorted(Comparator.comparingInt(ScheduledTripStop::getSeq))
                    .forEach(s -> {
                        Map<String, Object> stop = new HashMap<>();
                        stop.put("name", s.getStopName());
                        stop.put("lat",  s.getStopLat());
                        stop.put("lon",  s.getStopLon());
                        stops.add(stop);
                    });
        }
        result.put("stops", stops);
        return result;
    }
}