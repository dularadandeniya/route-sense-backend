package com.routesense.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "scheduled_trip_stop")
@Getter
@Setter
public class ScheduledTripStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scheduled_trip_id", nullable = false)
    private ScheduledTrip scheduledTrip;
    private Integer seq;
    private String stopName;
    private double stopLat;
    private double stopLon;
}