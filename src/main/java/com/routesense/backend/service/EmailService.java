package com.routesense.backend.service;

import com.routesense.backend.dto.EmailRequest;

public interface EmailService {

    void sendRouteEmail(EmailRequest request);
}
