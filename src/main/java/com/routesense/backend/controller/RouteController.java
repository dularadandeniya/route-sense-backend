package com.routesense.backend.controller;

import com.routesense.backend.dto.RouteRequest;
import com.routesense.backend.entity.Location;
import com.routesense.backend.service.LocationService;
import com.routesense.backend.service.OptimizationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
@RestController
@RequestMapping("/api/routes")
@CrossOrigin(origins = "http://localhost:5173")
public class RouteController {
    @Autowired
    private OptimizationService optimizationService;
    private LocationService locationService;

    @PostMapping("/locations")
    public Location saveLocation(@RequestBody Location location) {
        return locationService.saveLocation(location);
    }

    @GetMapping("/locations")
    public List<Location> getAllLocations() {
        return locationService.getAllLocations();
    }

    @PostMapping("/optimize")
    public List<Map<String, Object>> getOptimizedRoutes(@RequestBody RouteRequest request) {
        return optimizationService.findRoutesDynamic(request);
    }
}
