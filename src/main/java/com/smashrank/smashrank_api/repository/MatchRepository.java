package com.smashrank.smashrank_api.repository;

import com.smashrank.smashrank_api.model.Match;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MatchRepository extends JpaRepository<Match, String> {

    /**
     * Recent completed match history for a player (all characters).
     */
    @Query("""
        SELECT m FROM Match m
        WHERE m.status = 'COMPLETED'
        AND (m.player1Username = :username OR m.player2Username = :username)
        ORDER BY m.playedAt DESC
        """)
    List<Match> findRecentMatchesByUsername(@Param("username") String username, Pageable pageable);

    /**
     * Recent completed matches for a player with a specific character.
     */
    @Query("""
        SELECT m FROM Match m
        WHERE m.status = 'COMPLETED'
        AND (
            (m.player1Username = :username AND m.player1Character = :character)
            OR (m.player2Username = :username AND m.player2Character = :character)
        )
        ORDER BY m.playedAt DESC
        """)
    List<Match> findRecentMatchesByUsernameAndCharacter(
            @Param("username") String username,
            @Param("character") String character,
            Pageable pageable);

    @Query("""
        SELECT COUNT(m) FROM Match m
        WHERE m.status = 'COMPLETED'
        AND (m.player1Username = :username OR m.player2Username = :username)
        """)
    long countCompletedMatchesByUsername(@Param("username") String username);
}