package com.routesense.backend.service;

import com.routesense.backend.model.Location;

import java.util.List;

public interface LocationService {
    Location saveLocation(Location location);
    List<Location> getAllLocations();
}
