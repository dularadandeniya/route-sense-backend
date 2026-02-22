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

    // 1000 per day is very safe for your 100k monthly limit
    private final int dailyLimit = 1000;

    public String fetchMatrix(String coordinates) throws Exception {
        int currentUsage = getCount();
        int n = coordinates.split(";").length;
        int estimatedElements = n * n;

        if (currentUsage + estimatedElements > dailyLimit) {
            throw new Exception("Daily Mapbox limit reached!");
        }

        // Mapbox uses driving-traffic for live data
        // Format MUST be: longitude,latitude;longitude,latitude
        String url = "https://api.mapbox.com/directions-matrix/v1/mapbox/driving-traffic/"
                + coordinates
                + "?annotations=duration,distance&access_token=" + accessToken;

        String response = restTemplate.getForObject(url, String.class);

        saveCount(currentUsage + estimatedElements);
        return response;
    }

    private int getCount() {
        try {
            if (!Files.exists(quotaFile)) return 0;
            return Integer.parseInt(Files.readString(quotaFile).trim());
        } catch (Exception e) { return 0; }
    }

    private void saveCount(int count) {
        try { Files.writeString(quotaFile, String.valueOf(count)); } catch (Exception e) {}
    }
}