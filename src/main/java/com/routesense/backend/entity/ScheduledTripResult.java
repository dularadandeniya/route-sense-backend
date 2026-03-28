package com.routesense.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "scheduled_trip_result")
@Getter
@Setter
public class ScheduledTripResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scheduled_trip_id", nullable = false)
    private ScheduledTrip scheduledTrip;
    private String routeMode;
    private double totalTimeSeconds;
    private double totalCost;
    private double totalCo2;

    private Double avgTrafficFactor;
    private String explanation;

    private Boolean selectedRoute = false;
    private LocalDateTime createdAt = LocalDateTime.now();
}