package com.routesense.backend.service.impl;

import com.routesense.backend.model.Location;
import com.routesense.backend.repository.LocationRepository;
import com.routesense.backend.service.LocationService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LocationServiceImpl implements LocationService {
    private LocationRepository locationRepository;

    @Override
    public Location saveLocation(Location location) {
        return locationRepository.save(location);
    }

    @Override
    public List<Location> getAllLocations() {
        return locationRepository.findAll();
    }
}
