package com.smashrank.smashrank_api.repository;

import com.smashrank.smashrank_api.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlayerRepository extends JpaRepository<Player, Long> {
    Optional<Player> findByUsername(String username);

    /** Phase 2: Look up a player by their linked auth identity. */
    Optional<Player> findByUserId(UUID userId);
}