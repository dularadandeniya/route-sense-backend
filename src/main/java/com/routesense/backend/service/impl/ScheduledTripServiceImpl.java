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

import java.util.List;
import java.util.Map;

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
}