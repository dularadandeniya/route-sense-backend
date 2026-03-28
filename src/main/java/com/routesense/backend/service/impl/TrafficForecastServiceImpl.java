package com.routesense.backend.service.impl;

import com.routesense.backend.dto.TrafficPredictionRequest;
import com.routesense.backend.dto.TrafficPredictionResponse;
import com.routesense.backend.service.TrafficForecastService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class TrafficForecastServiceImpl implements TrafficForecastService {

    @Value("${routesense.ml.base-url:http://localhost:8001}")
    private String mlBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public double predictTrafficFactor(TrafficPredictionRequest request) {
        try {
            String url = mlBaseUrl + "/predict-traffic-factor";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<TrafficPredictionRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<TrafficPredictionResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST, entity, TrafficPredictionResponse.class);

            if (response.getBody() != null && response.getBody().getTrafficFactor() > 0) {
                return response.getBody().getTrafficFactor();
            }
        } catch (Exception e) {
            System.err.println("ML traffic prediction failed: " + e.getMessage());
        }

        return 1.20;
    }
}