package com.smashrank.smashrank_api.controller;

import com.smashrank.smashrank_api.service.AuthService;
import com.smashrank.smashrank_api.service.AuthService.AuthResult;
import com.smashrank.smashrank_api.service.AuthService.AuthException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    // =========================================================================
    // POST /api/auth/register
    // =========================================================================
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody AuthRequest request) {
        try {
            AuthResult result = authService.register(request.username(), request.password());
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (AuthException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage()));
        }
    }

    // =========================================================================
    // POST /api/auth/login
    // =========================================================================
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest request) {
        try {
            AuthResult result = authService.login(request.username(), request.password());
            return ResponseEntity.ok(result);
        } catch (AuthException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(e.getMessage()));
        }
    }

    // =========================================================================
    // POST /api/auth/refresh
    // =========================================================================
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshRequest request) {
        try {
            AuthResult result = authService.refresh(request.refreshToken());
            return ResponseEntity.ok(result);
        } catch (AuthException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(e.getMessage()));
        }
    }

    // =========================================================================
    // Request/Response DTOs
    // =========================================================================
    public record AuthRequest(String username, String password) {}
    public record RefreshRequest(String refreshToken) {}
    public record ErrorResponse(String error) {}
}