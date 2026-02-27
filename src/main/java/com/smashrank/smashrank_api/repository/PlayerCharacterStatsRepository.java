package com.smashrank.smashrank_api.repository;

import com.smashrank.smashrank_api.model.PlayerCharacterStats;
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

public interface PlayerCharacterStatsRepository extends JpaRepository<PlayerCharacterStats, Long> {

    // =========================================================================
    // Basic lookups
    // =========================================================================

    Optional<PlayerCharacterStats> findByPlayerIdAndCharacterName(Long playerId, String characterName);

    /** All character stats for a player, ordered by Elo descending (best first). */
    List<PlayerCharacterStats> findByPlayerIdOrderByEloDesc(Long playerId);

    // =========================================================================
    // Pessimistic locking for Elo updates
    // =========================================================================

    /**
     * Load character stats by IDs with pessimistic write lock, ordered by ID ASC.
     * CRITICAL: Always pass IDs in ascending order to prevent deadlocks.
     */
    @Query("SELECT pcs FROM PlayerCharacterStats pcs WHERE pcs.id IN :ids ORDER BY pcs.id ASC")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    List<PlayerCharacterStats> findAllByIdWithLock(@Param("ids") List<Long> ids);

    // =========================================================================
    // Per-character leaderboard
    // =========================================================================

    /**
     * Top players for a specific character, ordered by Elo descending.
     * Only includes stats with at least one game played.
     */
    @Query("""
        SELECT pcs FROM PlayerCharacterStats pcs
        WHERE pcs.characterName = :character
        AND (pcs.wins + pcs.losses) > 0
        ORDER BY pcs.elo DESC
        """)
    List<PlayerCharacterStats> findTopByCharacter(
            @Param("character") String character, Pageable pageable);

    /**
     * Count players who have played at least one game with a specific character.
     */
    @Query("""
        SELECT COUNT(pcs) FROM PlayerCharacterStats pcs
        WHERE pcs.characterName = :character
        AND (pcs.wins + pcs.losses) > 0
        """)
    long countActiveByCharacter(@Param("character") String character);

    /**
     * Get a player's rank for a specific character.
     * Returns count of players with higher Elo for that character.
     */
    @Query("""
        SELECT COUNT(pcs) + 1 FROM PlayerCharacterStats pcs
        WHERE pcs.characterName = :character
        AND pcs.elo > :elo
        AND (pcs.wins + pcs.losses) > 0
        """)
    long getCharacterRank(@Param("character") String character, @Param("elo") int elo);

    // =========================================================================
    // Global leaderboard support
    // =========================================================================

    /**
     * Get the highest Elo across all characters for a specific player.
     * Used to update the denormalized players.elo field.
     */
    @Query("SELECT MAX(pcs.elo) FROM PlayerCharacterStats pcs WHERE pcs.playerId = :playerId")
    Optional<Integer> findMaxEloByPlayerId(@Param("playerId") Long playerId);

    /**
     * Get all distinct character names that have been played.
     * Useful for populating character filter dropdowns.
     */
    @Query("SELECT DISTINCT pcs.characterName FROM PlayerCharacterStats pcs ORDER BY pcs.characterName")
    List<String> findAllPlayedCharacters();

    /**
     * Get a player's most-played character (by total games).
     * Used for "main" determination on profile.
     */
    @Query("""
        SELECT pcs FROM PlayerCharacterStats pcs
        WHERE pcs.playerId = :playerId
        ORDER BY (pcs.wins + pcs.losses) DESC, pcs.elo DESC
        """)
    List<PlayerCharacterStats> findByPlayerIdOrderByGamesDesc(@Param("playerId") Long playerId, Pageable pageable);
}