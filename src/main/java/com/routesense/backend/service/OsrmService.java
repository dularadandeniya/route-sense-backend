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

    // Base URL for local Docker OSRM
    private static final String OSRM_BASE_URL = "http://localhost:5000/route/v1/driving/";

    public record RouteMetrics(double durationSeconds, double distanceMeters) {}


    public Map<String, Object> getRoute(double lat1, double lon1, double lat2, double lon2) {
        // Construct standard OSRM
        String coordinates = lon1 + "," + lat1 + ";" + lon2 + "," + lat2;

        // request "polyline" explicitly for the decoder
        String url = OSRM_BASE_URL + coordinates + "?overview=full&geometries=polyline";

        try {
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            if (root.path("code").asText().equals("Ok")) {
                JsonNode route = root.path("routes").get(0);
                Map<String, Object> mapData = new HashMap<>();

                mapData.put("duration", route.path("duration").asDouble());
                mapData.put("distanceMeters", route.path("distance").asDouble());
                mapData.put("geometry", route.path("geometry").asText());

                return mapData;
            }
        } catch (Exception e) {
            System.err.println("Error fetching OSRM Route: " + e.getMessage());
        }
        return null;
    }


    public List<Map<String, Object>> getRouteAlternatives(double lat1, double lon1, double lat2, double lon2) {
        String coordinates = lon1 + "," + lat1 + ";" + lon2 + "," + lat2;

        String url = OSRM_BASE_URL + coordinates + "?alternatives=true&overview=full&geometries=polyline";

        List<Map<String, Object>> routeOptions = new ArrayList<>();

        try {
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            if (root.path("code").asText().equals("Ok")) {
                JsonNode routes = root.path("routes");

                for (JsonNode route : routes) {
                    Map<String, Object> mapData = new HashMap<>();
                    mapData.put("duration", route.path("duration").asDouble());
                    mapData.put("distanceMeters", route.path("distance").asDouble());
                    mapData.put("geometry", route.path("geometry").asText()); // Get encoded string
                    routeOptions.add(mapData);
                }
            }
        } catch (Exception e) {
            System.err.println("Error calling OSRM Alternatives: " + e.getMessage());
        }
        return routeOptions;
    }


    public RouteMetrics getRouteMetrics(double lat1, double lon1, double lat2, double lon2, double trafficFactor) {
        String coordinates = lon1 + "," + lat1 + ";" + lon2 + "," + lat2;
        String url = OSRM_BASE_URL + coordinates + "?overview=false"; // No geometry needed here

        try {
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            if (root.path("code").asText().equals("Ok")) {
                JsonNode route0 = root.path("routes").get(0);
                double baseDuration = route0.path("duration").asDouble();
                double distanceMeters = route0.path("distance").asDouble();

                double adjustedDuration = baseDuration * (trafficFactor > 0 ? trafficFactor : 1.0);
                return new RouteMetrics(adjustedDuration, distanceMeters);
            }

        } catch (Exception e) {
            System.err.println("Error calling OSRM metrics: " + e.getMessage());
        }
        return new RouteMetrics(-1.0, -1.0);
    }
}