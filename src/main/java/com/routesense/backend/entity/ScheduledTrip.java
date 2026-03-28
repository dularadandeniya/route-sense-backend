package com.routesense.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "scheduled_trip")
@Getter
@Setter
public class ScheduledTrip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private String tripName;
    private String startName;
    private double startLat;
    private double startLon;
    private String endName;
    private double endLat;
    private double endLon;
    private LocalDateTime departureTime;
    private String vehicleType;
    private double payloadKg;
    private String status = "DRAFT";
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    @OneToMany(mappedBy = "scheduledTrip", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq ASC")
    private List<ScheduledTripStop> stops = new ArrayList<>();
}