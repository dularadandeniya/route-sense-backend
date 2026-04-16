package com.routesense.backend.service.impl;

import com.resend.*;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.routesense.backend.dto.EmailRequest;
import com.routesense.backend.service.EmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EmailServiceImpl implements EmailService {

    private final Resend resend;

    public EmailServiceImpl(@Value("${resend.api-key}") String apiKey) {
        this.resend = new Resend(apiKey);
    }

    @Override
    public void sendRouteEmail(EmailRequest request) {
        String mapsSection = (request.getGoogleMapsUrl() != null && !request.getGoogleMapsUrl().isEmpty())
                ? "\n--- Open in Google Maps ---\n" + request.getGoogleMapsUrl() + "\n\n"
                : "";

        String tripLabel = (request.getTripName() != null && !request.getTripName().isEmpty())
                ? request.getTripName()
                : "Your Trip";

        String emailBody =
                "Hello Driver,\n\n" +
                        "A new optimized route has been assigned to you.\n\n" +
                        "Trip: " + tripLabel + "\n\n" +
                        "--- Route Summary ---\n" +
                        "Route Strategy: " + request.getMode() + "\n" +
                        "Estimated Time: " + request.getTime() + "\n\n" +
                        "--- Stop Sequence ---\n" +
                        request.getStops() + "\n\n" +
                        mapsSection +
                        "Tap the Google Maps link above to open turn-by-turn navigation.\n\n" +
                        "Drive safely!\n\n" +
                        "Best Regards,\n" +
                        "RouteSense Dispatch Team";

        CreateEmailOptions params = CreateEmailOptions.builder()
                .from("RouteSense <onboarding@resend.dev>")
                .to(request.getDriverEmail())
                .subject("Route Assigned: " + tripLabel + " (" + request.getMode() + ")")
                .text(emailBody)
                .build();

        try {
            resend.emails().send(params);
        } catch (ResendException e) {
            throw new RuntimeException("Failed to send email: " + e.getMessage(), e);
        }
    }
}