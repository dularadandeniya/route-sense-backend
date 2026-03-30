package com.routesense.backend.service;

import com.routesense.backend.dto.JwtResponse;
import com.routesense.backend.dto.LoginRequest;
import com.routesense.backend.dto.SignupRequest;
import org.springframework.stereotype.Service;


public interface AuthService {
    JwtResponse authenticateUser(LoginRequest loginRequest);
    String registerUser(SignupRequest signUpRequest);
}
