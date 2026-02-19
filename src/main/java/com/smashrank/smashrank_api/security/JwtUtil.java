package com.smashrank.smashrank_api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtil {

    private final SecretKey signingKey;
    private final long accessTokenExpirationMs;

    public JwtUtil(
            @Value("${smashrank.jwt.secret}") String secret,
            @Value("${smashrank.jwt.access-token-expiration-ms:3600000}") long accessTokenExpirationMs) {
        // HMAC-SHA key derived from the configured secret
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationMs = accessTokenExpirationMs;
    }

    /**
     * Generate an access token containing the user's ID and username.
     */
    public String generateAccessToken(UUID userId, String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpirationMs);

        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /**
     * Extract the user ID (subject) from a valid token.
     * Returns null if the token is invalid or expired.
     */
    public UUID extractUserId(String token) {
        Claims claims = parseClaims(token);
        return claims != null ? UUID.fromString(claims.getSubject()) : null;
    }

    /**
     * Extract the username claim from a valid token.
     */
    public String extractUsername(String token) {
        Claims claims = parseClaims(token);
        return claims != null ? claims.get("username", String.class) : null;
    }

    /**
     * Validate a token: checks signature, expiration, and structure.
     */
    public boolean isTokenValid(String token) {
        return parseClaims(token) != null;
    }

    private Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            // Token is invalid, expired, or malformed
            return null;
        }
    }
}