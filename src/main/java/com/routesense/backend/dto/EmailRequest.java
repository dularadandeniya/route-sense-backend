package com.routesense.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailRequest {
    private String driverEmail;
    private String mode;
    private String time;
    private String stops;
    private String tripName;
    private String googleMapsUrl;
}