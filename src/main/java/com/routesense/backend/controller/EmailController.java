package com.routesense.backend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/email")
public class EmailController {

    @Autowired
    private JavaMailSender mailSender;

    @PostMapping("/send-route")
    public ResponseEntity<?> sendRouteEmail(@RequestBody Map<String, String> payload) {
        try {
            String to = payload.get("driverEmail");
            String mode = payload.get("mode");
            String time = payload.get("time");
            String stops = payload.get("stops");

            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject("New Route Assigned: " + mode);
            message.setText("Hello Driver,\n\n" +
                    "Please find your optimized route details below:\n\n" +
                    "Route Strategy: " + mode + "\n" +
                    "Estimated Time: " + time + " minutes\n" +
                    "Stop Sequence: " + stops + "\n\n" +
                    "Drive safely,\nRouteSense Dispatch Team");

            mailSender.send(message);
            return ResponseEntity.ok().body("Email sent successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to send email: " + e.getMessage());
        }
    }
}