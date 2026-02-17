package com.routesense.backend.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RouteNode {
    private String name;
    private double latitude;
    private double longitude;

    public RouteNode() {

    }

    public void setCustomerName(String startName) {
    }
}
