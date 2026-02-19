package com.smashrank.smashrank_api.service;

import com.smashrank.smashrank_api.model.Player;
import com.smashrank.smashrank_api.model.RefreshToken;
import com.smashrank.smashrank_api.model.User;
import com.smashrank.smashrank_api.repository.PlayerRepository;
import com.smashrank.smashrank_api.repository.RefreshTokenRepository;
import com.smashrank.smashrank_api.repository.UserRepository;
import com.smashrank.smashrank_api.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PlayerRepository playerRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final long refreshTokenExpirationDays;

    public AuthService(UserRepository userRepository,
                       PlayerRepository playerRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       @Value("${smashrank.jwt.refresh-token-expiration-days:30}") long refreshTokenExpirationDays) {
        this.userRepository = userRepository;
        this.playerRepository = playerRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.refreshTokenExpirationDays = refreshTokenExpirationDays;
    }

    // =========================================================================
    // Register
    // =========================================================================

    @Transactional
    public AuthResult register(String username, String password) {
        // Validate input
        if (username == null || username.isBlank()) {
            throw new AuthException("Username is required.");
        }
        if (password == null || password.length() < 6) {
            throw new AuthException("Password must be at least 6 characters.");
        }
        if (username.length() > 20) {
            throw new AuthException("Username must be 20 characters or fewer.");
        }
        if (!username.matches("^[a-zA-Z0-9_]+$")) {
            throw new AuthException("Username can only contain letters, numbers, and underscores.");
        }
        if (userRepository.existsByUsername(username)) {
            throw new AuthException("Username is already taken.");
        }

        // Create User (auth identity)
        User user = new User(username, passwordEncoder.encode(password));
        userRepository.save(user);

        // Create linked Player profile (gaming stats, starts at 1200 Elo)
        // Phase 2: Player now gets the userId FK set.
        Player player = new Player(username, username);
        player.setUserId(user.getId());
        playerRepository.save(player);

        // Generate tokens
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername());
        RefreshToken refreshToken = createRefreshToken(user);

        return new AuthResult(accessToken, refreshToken.getToken(), user.getId(), user.getUsername());
    }

    // =========================================================================
    // Login
    // =========================================================================

    public AuthResult login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new AuthException("Invalid username or password."));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new AuthException("Invalid username or password.");
        }

        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername());
        RefreshToken refreshToken = createRefreshToken(user);

        return new AuthResult(accessToken, refreshToken.getToken(), user.getId(), user.getUsername());
    }

    // =========================================================================
    // Refresh
    // =========================================================================

    @Transactional
    public AuthResult refresh(String refreshTokenStr) {
        RefreshToken storedToken = refreshTokenRepository.findByToken(refreshTokenStr)
                .orElseThrow(() -> new AuthException("Invalid refresh token."));

        if (!storedToken.isValid()) {
            throw new AuthException("Refresh token is expired or revoked.");
        }

        // Rotate: revoke the old token, issue a new pair
        storedToken.setRevoked(true);
        refreshTokenRepository.save(storedToken);

        User user = storedToken.getUser();
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername());
        RefreshToken newRefreshToken = createRefreshToken(user);

        return new AuthResult(accessToken, newRefreshToken.getToken(), user.getId(), user.getUsername());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private RefreshToken createRefreshToken(User user) {
        String token = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(refreshTokenExpirationDays);
        RefreshToken refreshToken = new RefreshToken(token, user, expiresAt);
        return refreshTokenRepository.save(refreshToken);
    }

    // =========================================================================
    // DTOs & Exception
    // =========================================================================

    public record AuthResult(String accessToken, String refreshToken, UUID userId, String username) {}

    public static class AuthException extends RuntimeException {
        public AuthException(String message) {
            super(message);
        }
    }
}