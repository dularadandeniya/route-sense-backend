package com.routesense.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class MapboxApiService {

    @Value("${mapbox.access.token}")
    private String accessToken;

    private final RestTemplate restTemplate = new RestTemplate();
    private final Path quotaFile = Paths.get("mapbox_quota.txt");

    private final int dailyLimit = 3000;

    public String fetchMatrixTraffic(String coordinates) throws Exception {
        return fetchMatrixInternal("driving-traffic", coordinates);
    }

    public String fetchMatrixBase(String coordinates) throws Exception {
        return fetchMatrixInternal("driving", coordinates);
    }

    private String fetchMatrixInternal(String profile, String coordinates) throws Exception {
        int currentUsage = getCount();

        int n = coordinates.split(";").length;
        int estimatedElements = n * n;

        if (currentUsage + estimatedElements > dailyLimit) {
            throw new Exception("Daily Mapbox Matrix limit reached!");
        }

        String url = "https://api.mapbox.com/directions-matrix/v1/mapbox/" + profile + "/"
                + coordinates
                + "?annotations=duration,distance&access_token=" + accessToken;

        String response = restTemplate.getForObject(url, String.class);

        saveCount(currentUsage + estimatedElements);
        return response;
    }


    public String fetchDirectionsGeoJson(String coordinates) throws Exception {
        String url = "https://api.mapbox.com/directions/v5/mapbox/driving-traffic/"
                + coordinates
                + "?overview=full&geometries=geojson&access_token=" + accessToken;

        return restTemplate.getForObject(url, String.class);
    }

    private int getCount() {
        try {
            if (!Files.exists(quotaFile)) return 0;
            String s = Files.readString(quotaFile).trim();
            if (s.isBlank()) return 0;
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private void saveCount(int count) {
        try {
            Files.writeString(quotaFile, String.valueOf(count));
        } catch (Exception ignored) {}
    }
}