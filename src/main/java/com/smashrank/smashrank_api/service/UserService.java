package com.smashrank.smashrank_api.service;

import com.smashrank.smashrank_api.model.Player;
import com.smashrank.smashrank_api.model.User;
import com.smashrank.smashrank_api.repository.PlayerRepository;
import com.smashrank.smashrank_api.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Lookup service for resolving between userId, username, and Player.
 * Used during the migration period where some code uses usernames
 * and other code uses UUIDs.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final PlayerRepository playerRepository;

    public UserService(UserRepository userRepository, PlayerRepository playerRepository) {
        this.userRepository = userRepository;
        this.playerRepository = playerRepository;
    }

    /** Look up a User's UUID by their username. Returns null if not found. */
    public UUID getUserIdByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElse(null);
    }

    /** Look up a username by userId. Returns null if not found. */
    public String getUsernameByUserId(UUID userId) {
        return userRepository.findById(userId)
                .map(User::getUsername)
                .orElse(null);
    }

    /** Look up a User by username. */
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /** Look up a Player by their linked userId. */
    public Optional<Player> findPlayerByUserId(UUID userId) {
        return playerRepository.findByUserId(userId);
    }

    /** Look up a Player by username. */
    public Optional<Player> findPlayerByUsername(String username) {
        return playerRepository.findByUsername(username);
    }
}