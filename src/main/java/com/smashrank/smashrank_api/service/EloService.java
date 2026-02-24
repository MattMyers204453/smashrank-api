package com.smashrank.smashrank_api.service;

import com.smashrank.smashrank_api.model.Match;
import com.smashrank.smashrank_api.model.Player;
import com.smashrank.smashrank_api.repository.PlayerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Stream;

/**
 * Handles Elo rating updates after match completion.
 *
 * Key design decisions:
 * - Pessimistic locking with ordered IDs prevents lost updates and deadlocks.
 * - Both players' ratings update in a single transaction (atomic).
 * - Match entity stores full audit trail (before/after Elo, K-factor, deltas).
 * - Player.updateElo() handles peak_elo tracking automatically.
 */
@Service
public class EloService {

    private final PlayerRepository playerRepository;

    public EloService(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    // =========================================================================
    // Core: Process a completed match and update both players' Elo
    // =========================================================================

    /**
     * Atomically updates both players' Elo ratings after a COMPLETED match.
     *
     * Called from MatchController.confirmResult() when both players agree on the winner.
     * The Match entity is updated in-place with Elo audit fields before being saved
     * by the caller.
     *
     * @param match  The match entity (must have player1/player2 usernames and winner set)
     * @return EloResult with the computed deltas for WebSocket notification
     */
    @Transactional
    public EloResult processMatchResult(Match match) {
        String winnerUsername = match.getWinnerUsername();
        String p1Username = match.getPlayer1Username();
        String p2Username = match.getPlayer2Username();

        // 1. Load both players with pessimistic lock (ordered by ID to prevent deadlock)
        Player p1 = playerRepository.findByUsername(p1Username)
                .orElseThrow(() -> new RuntimeException("Player not found: " + p1Username));
        Player p2 = playerRepository.findByUsername(p2Username)
                .orElseThrow(() -> new RuntimeException("Player not found: " + p2Username));

        // Lock in consistent order (lower ID first) to prevent deadlocks
        List<Long> orderedIds = Stream.of(p1.getId(), p2.getId()).sorted().toList();
        List<Player> lockedPlayers = playerRepository.findAllByIdWithLock(orderedIds);

        // Re-resolve after lock (the locked entities are the authoritative ones)
        Player lockedP1 = lockedPlayers.stream()
                .filter(p -> p.getId().equals(p1.getId())).findFirst().orElseThrow();
        Player lockedP2 = lockedPlayers.stream()
                .filter(p -> p.getId().equals(p2.getId())).findFirst().orElseThrow();

        // 2. Determine who won
        boolean p1Won = p1Username.equals(winnerUsername);

        // 3. Snapshot before-ratings
        int p1EloBefore = lockedP1.getElo();
        int p2EloBefore = lockedP2.getElo();
        int p1Games = lockedP1.getWins() + lockedP1.getLosses();
        int p2Games = lockedP2.getWins() + lockedP2.getLosses();

        // 4. Calculate new ratings
        int p1EloAfter = EloCalculator.calculateNewRating(p1EloBefore, p2EloBefore, p1Won, p1Games);
        int p2EloAfter = EloCalculator.calculateNewRating(p2EloBefore, p1EloBefore, !p1Won, p2Games);

        // Use the higher K-factor for audit trail (both players may have different K)
        int p1K = EloCalculator.getKFactor(p1Games);
        int p2K = EloCalculator.getKFactor(p2Games);

        // 5. Update players
        lockedP1.updateElo(p1EloAfter);
        lockedP2.updateElo(p2EloAfter);

        if (p1Won) {
            lockedP1.recordWin();
            lockedP2.recordLoss();
        } else {
            lockedP1.recordLoss();
            lockedP2.recordWin();
        }

        // 6. Set audit fields on match
        match.setPlayer1EloBefore(p1EloBefore);
        match.setPlayer1EloAfter(p1EloAfter);
        match.setPlayer2EloBefore(p2EloBefore);
        match.setPlayer2EloAfter(p2EloAfter);
        match.setPlayer1KFactor(p1K);
        match.setPlayer2KFactor(p2K);

        // JPA dirty checking will flush the player updates on transaction commit.
        // The caller saves the Match entity.

        return new EloResult(
                p1Username, p2Username,
                p1EloBefore, p1EloAfter, p1EloAfter - p1EloBefore,
                p2EloBefore, p2EloAfter, p2EloAfter - p2EloBefore
        );
    }

    // =========================================================================
    // Result DTO
    // =========================================================================

    /**
     * Carries Elo changes back to the controller for WebSocket notification.
     */
    public record EloResult(
            String player1Username,
            String player2Username,
            int player1EloBefore, int player1EloAfter, int player1Delta,
            int player2EloBefore, int player2EloAfter, int player2Delta
    ) {
        /** Get the delta for a specific player by username. */
        public int getDeltaForPlayer(String username) {
            if (username.equals(player1Username)) return player1Delta;
            if (username.equals(player2Username)) return player2Delta;
            return 0;
        }

        /** Get the new Elo for a specific player by username. */
        public int getNewEloForPlayer(String username) {
            if (username.equals(player1Username)) return player1EloAfter;
            if (username.equals(player2Username)) return player2EloAfter;
            return 0;
        }
    }
}