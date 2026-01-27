package com.routesense.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OsrmService {
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // The URL of your local Docker OSRM server
    private final String OSRM_URL = "http://localhost:5000/route/v1/driving/";

    public double getDuration(double lat1, double lon1, double lat2, double lon2) {
        // OSRM requires "Longitude,Latitude" order (opposite of Google Maps)
        String coordinates = lon1 + "," + lat1 + ";" + lon2 + "," + lat2;
        String url = OSRM_URL + coordinates + "?overview=false";

        try {
            // 1. Call the server
            String response = restTemplate.getForObject(url, String.class);

            // 2. Parse the JSON answer
            JsonNode root = objectMapper.readTree(response);

            // 3. Extract "duration" (in seconds) from the first route found
            return root.path("routes").get(0).path("duration").asDouble();

        } catch (Exception e) {
            System.out.println("Error calling OSRM: " + e.getMessage());
            return -1.0; // Return -1 if something breaks
        }
    }
}
