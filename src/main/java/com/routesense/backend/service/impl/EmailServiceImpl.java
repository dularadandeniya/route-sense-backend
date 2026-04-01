package com.routesense.backend.service.impl;

import com.routesense.backend.dto.EmailRequest;
import com.routesense.backend.service.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailServiceImpl implements EmailService {

    @Autowired
    private JavaMailSender mailSender;

    @Override
    public void sendRouteEmail(EmailRequest request) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(request.getDriverEmail());
        message.setSubject("New Route Assigned: " + request.getMode());

        String emailBody =
                "Hello Driver,\n\n" +
                        "A new optimized route has been assigned to you.\n\n" +
                        "--- ROUTE SUMMARY ---\n" +
                        "Route Strategy: " + request.getMode() + "\n" +
                        "Estimated  Time: " + request.getTime() + " minutes\n\n" +
                        "--- STOP SEQUENCE ---\n" +
                        request.getStops() + "\n\n" +
                        "Please follow this sequence for the best efficiency.\n" +
                        "Drive safely!\n\n" +
                        "Best Regards,\n" +
                        "RouteSense Dispatch Team";

        message.setText(emailBody);
        mailSender.send(message);
    }
}
