package com.routesense.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class ScheduledTripResponse {
    private Long id;
    private String tripName;
    private LocalDateTime departureTime;
    private String status;
}