package com.smashrank.smashrank_api.repository;

import com.smashrank.smashrank_api.model.Player;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    Optional<Player> findByUsername(String username);

    /** Phase 2: Look up a player by their linked auth identity. */
    Optional<Player> findByUserId(UUID userId);

    // =========================================================================
    // Pessimistic locking for Elo updates
    // =========================================================================

    /**
     * Load players by ID with pessimistic write lock, ordered by ID ASC.
     *
     * CRITICAL: Always pass IDs in ascending order to prevent deadlocks.
     * Uses PESSIMISTIC_WRITE which maps to SELECT ... FOR NO KEY UPDATE in PostgreSQL.
     * Lock timeout of 5 seconds prevents indefinite blocking.
     */
    @Query("SELECT p FROM Player p WHERE p.id IN :ids ORDER BY p.id ASC")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    List<Player> findAllByIdWithLock(@Param("ids") List<Long> ids);

    // =========================================================================
    // Rankings / Leaderboard queries
    // =========================================================================

    /**
     * Global leaderboard — top players by Elo, descending.
     * Only includes players who have played at least one game.
     */
    @Query("SELECT p FROM Player p WHERE (p.wins + p.losses) > 0 ORDER BY p.elo DESC")
    List<Player> findTopByElo(Pageable pageable);

    /**
     * Count total players with at least one game (for "rank X of Y" display).
     */
    @Query("SELECT COUNT(p) FROM Player p WHERE (p.wins + p.losses) > 0")
    long countActivePlayers();

    /**
     * Get a player's rank (1-indexed). Returns the count of players with higher Elo.
     */
    @Query("SELECT COUNT(p) + 1 FROM Player p WHERE p.elo > :elo AND (p.wins + p.losses) > 0")
    long getPlayerRank(@Param("elo") int elo);
}