package com.smashrank.smashrank_api.repository;

import com.smashrank.smashrank_api.model.Player;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    Optional<Player> findByUsername(String username);
    Optional<Player> findByUserId(UUID userId);

    // =========================================================================
    // Global leaderboard (uses denormalized elo = max character Elo)
    // =========================================================================

    @Query("SELECT p FROM Player p WHERE (p.wins + p.losses) > 0 ORDER BY p.elo DESC")
    List<Player> findTopByElo(Pageable pageable);

    @Query("SELECT COUNT(p) FROM Player p WHERE (p.wins + p.losses) > 0")
    long countActivePlayers();

    @Query("SELECT COUNT(p) + 1 FROM Player p WHERE p.elo > :elo AND (p.wins + p.losses) > 0")
    long getPlayerRank(@Param("elo") int elo);
}