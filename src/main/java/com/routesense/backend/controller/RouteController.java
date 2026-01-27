package com.routesense.backend.controller;

import com.routesense.backend.service.OptimizationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
@RestController
@RequestMapping("/api/routes")
public class RouteController {
    @Autowired
    private OptimizationService optimizationService;

    // Endpoint: http://localhost:8080/api/routes/optimize
    @GetMapping("/optimize")
    public List<Map<String, Object>> generateRoutes() {
        return optimizationService.findOptimalRoutes();
    }
}
