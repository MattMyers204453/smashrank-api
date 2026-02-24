package com.smashrank.smashrank_api.repository;

import com.smashrank.smashrank_api.model.Match;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MatchRepository extends JpaRepository<Match, String> {

    /**
     * Get recent match history for a player (both as player1 and player2).
     * Only returns COMPLETED matches (with Elo data).
     * Ordered by most recent first.
     */
    @Query("""
        SELECT m FROM Match m
        WHERE m.status = 'COMPLETED'
        AND (m.player1Username = :username OR m.player2Username = :username)
        ORDER BY m.playedAt DESC
        """)
    List<Match> findRecentMatchesByUsername(@Param("username") String username, Pageable pageable);

    /**
     * Count total completed matches for a player.
     */
    @Query("""
        SELECT COUNT(m) FROM Match m
        WHERE m.status = 'COMPLETED'
        AND (m.player1Username = :username OR m.player2Username = :username)
        """)
    long countCompletedMatchesByUsername(@Param("username") String username);
}