package com.routesense.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class OsrmService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String OSRM_URL = "http://localhost:5000/route/v1/driving/";

    public record RouteMetrics(double durationSeconds, double distanceMeters) {}


    public List<Map<String, Object>> getRouteAlternatives(double lat1, double lon1, double lat2, double lon2) {
        String coordinates = lon1 + "," + lat1 + ";" + lon2 + "," + lat2;
        String url = OSRM_URL + coordinates + "?alternatives=true&overview=full&geometries=geojson";

        List<Map<String, Object>> routeOptions = new ArrayList<>();

        try {
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode routes = root.path("routes");

            // Loop through all routes returned by OSRM
            for (JsonNode route : routes) {
                Map<String, Object> mapData = new HashMap<>();


                double duration = route.path("duration").asDouble();
                double distanceMeters = route.path("distance").asDouble();
                String geometry = route.path("geometry").toString();

                mapData.put("duration", duration);
                mapData.put("distanceMeters", distanceMeters);
                mapData.put("geometry", geometry);

                routeOptions.add(mapData);
            }
        } catch (Exception e) {
            System.out.println("Error calling OSRM Alternatives: " + e.getMessage());
        }
        return routeOptions;
    }


    public RouteMetrics getRouteMetrics(double lat1, double lon1, double lat2, double lon2, double trafficFactor) {
        String coordinates = lon1 + "," + lat1 + ";" + lon2 + "," + lat2;
        String url = OSRM_URL + coordinates + "?overview=false";

        try {
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            JsonNode route0 = root.path("routes").get(0);
            double baseDuration = route0.path("duration").asDouble();
            double distanceMeters = route0.path("distance").asDouble();

            double adjustedDuration = baseDuration * (trafficFactor > 0 ? trafficFactor : 1.0);

            return new RouteMetrics(adjustedDuration, distanceMeters);

        } catch (Exception e) {
            System.out.println("Error calling OSRM metrics: " + e.getMessage());
            return new RouteMetrics(-1.0, -1.0);
        }
    }

}
