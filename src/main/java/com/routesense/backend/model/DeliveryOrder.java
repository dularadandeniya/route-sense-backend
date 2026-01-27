package com.routesense.backend.model;

import jakarta.persistence.*;
import lombok.Data;

@Data // Auto-generates Getters & Setters
@Entity // Tells Java this is a Database Table
@Table(name = "delivery_orders")
public class DeliveryOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String customerName;
    private String address;

    // The most important part for your Algorithm:
    private double latitude;
    private double longitude;
    private double weightKg;

    // Time Windows (e.g., 0900 to 1700)
    private int readyTime;
    private int dueTime;
}