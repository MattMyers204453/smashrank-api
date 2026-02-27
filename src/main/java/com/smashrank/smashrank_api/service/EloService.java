package com.smashrank.smashrank_api.service;

import com.smashrank.smashrank_api.model.Match;
import com.smashrank.smashrank_api.model.Player;
import com.smashrank.smashrank_api.model.PlayerCharacterStats;
import com.smashrank.smashrank_api.repository.PlayerCharacterStatsRepository;
import com.smashrank.smashrank_api.repository.PlayerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Stream;

/**
 * Handles per-character Elo rating updates after match completion.
 *
 * Flow:
 * 1. Look up (or create) PlayerCharacterStats for each player's character.
 * 2. Pessimistic lock both character stat rows (ordered by ID).
 * 3. Compute new Elo using each player's character-specific rating and game count.
 * 4. Update character stats + win/loss counters.
 * 5. Sync the denormalized players.elo field (= max of all character Elos).
 * 6. Set audit fields on the Match entity.
 */
@Service
public class EloService {

    private final PlayerRepository playerRepository;
    private final PlayerCharacterStatsRepository charStatsRepository;

    public EloService(PlayerRepository playerRepository,
                      PlayerCharacterStatsRepository charStatsRepository) {
        this.playerRepository = playerRepository;
        this.charStatsRepository = charStatsRepository;
    }

    // =========================================================================
    // Core: Process a completed match
    // =========================================================================

    @Transactional
    public EloResult processMatchResult(Match match) {
        String winnerUsername = match.getWinnerUsername();
        String p1Username = match.getPlayer1Username();
        String p2Username = match.getPlayer2Username();
        String p1Character = match.getPlayer1Character();
        String p2Character = match.getPlayer2Character();

        // 1. Load players to get their IDs
        Player p1 = playerRepository.findByUsername(p1Username)
                .orElseThrow(() -> new RuntimeException("Player not found: " + p1Username));
        Player p2 = playerRepository.findByUsername(p2Username)
                .orElseThrow(() -> new RuntimeException("Player not found: " + p2Username));

        // 2. Get or create character stats for each player
        PlayerCharacterStats p1Stats = getOrCreateCharStats(p1.getId(), p1Character);
        PlayerCharacterStats p2Stats = getOrCreateCharStats(p2.getId(), p2Character);

        // 3. Pessimistic lock both character stat rows (ordered by ID to prevent deadlock)
        List<Long> orderedIds = Stream.of(p1Stats.getId(), p2Stats.getId()).sorted().toList();
        List<PlayerCharacterStats> locked = charStatsRepository.findAllByIdWithLock(orderedIds);

        PlayerCharacterStats lockedP1Stats = locked.stream()
                .filter(s -> s.getId().equals(p1Stats.getId())).findFirst().orElseThrow();
        PlayerCharacterStats lockedP2Stats = locked.stream()
                .filter(s -> s.getId().equals(p2Stats.getId())).findFirst().orElseThrow();

        // 4. Determine winner
        boolean p1Won = p1Username.equals(winnerUsername);

        // 5. Snapshot before-ratings (character-specific)
        int p1EloBefore = lockedP1Stats.getElo();
        int p2EloBefore = lockedP2Stats.getElo();
        int p1Games = lockedP1Stats.getTotalGames();
        int p2Games = lockedP2Stats.getTotalGames();

        // 6. Calculate new character ratings
        int p1EloAfter = EloCalculator.calculateNewRating(p1EloBefore, p2EloBefore, p1Won, p1Games);
        int p2EloAfter = EloCalculator.calculateNewRating(p2EloBefore, p1EloBefore, !p1Won, p2Games);

        int p1K = EloCalculator.getKFactor(p1Games);
        int p2K = EloCalculator.getKFactor(p2Games);

        // 7. Update character stats
        lockedP1Stats.updateElo(p1EloAfter);
        lockedP2Stats.updateElo(p2EloAfter);

        if (p1Won) {
            lockedP1Stats.recordWin();
            lockedP2Stats.recordLoss();
        } else {
            lockedP1Stats.recordLoss();
            lockedP2Stats.recordWin();
        }

        // 8. Sync denormalized global Elo on Player entities
        //    players.elo = MAX of all their character Elos
        syncGlobalElo(p1);
        syncGlobalElo(p2);

        // 9. Update win/loss counters on Player (aggregate across all characters)
        if (p1Won) {
            p1.recordWin();
            p2.recordLoss();
        } else {
            p1.recordLoss();
            p2.recordWin();
        }

        // 10. Set audit fields on match
        match.setPlayer1EloBefore(p1EloBefore);
        match.setPlayer1EloAfter(p1EloAfter);
        match.setPlayer2EloBefore(p2EloBefore);
        match.setPlayer2EloAfter(p2EloAfter);
        match.setPlayer1KFactor(p1K);
        match.setPlayer2KFactor(p2K);

        return new EloResult(
                p1Username, p2Username,
                p1Character, p2Character,
                p1EloBefore, p1EloAfter, p1EloAfter - p1EloBefore,
                p2EloBefore, p2EloAfter, p2EloAfter - p2EloBefore
        );
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Get existing character stats or create a new row at default 1200 Elo.
     * This means a player's first game with a new character starts at 1200,
     * not their global rating.
     */
    private PlayerCharacterStats getOrCreateCharStats(Long playerId, String characterName) {
        return charStatsRepository.findByPlayerIdAndCharacterName(playerId, characterName)
                .orElseGet(() -> {
                    PlayerCharacterStats newStats = new PlayerCharacterStats(playerId, characterName);
                    return charStatsRepository.save(newStats);
                });
    }

    /**
     * Recalculate and sync the denormalized players.elo field.
     * This is the player's highest character Elo, used for the global leaderboard.
     */
    private void syncGlobalElo(Player player) {
        Integer maxElo = charStatsRepository.findMaxEloByPlayerId(player.getId())
                .orElse(EloCalculator.getDefaultRating());
        player.updateElo(maxElo);
        // JPA dirty checking handles the save
    }

    // =========================================================================
    // Result DTO
    // =========================================================================

    public record EloResult(
            String player1Username, String player2Username,
            String player1Character, String player2Character,
            int player1EloBefore, int player1EloAfter, int player1Delta,
            int player2EloBefore, int player2EloAfter, int player2Delta
    ) {
        public int getDeltaForPlayer(String username) {
            if (username.equals(player1Username)) return player1Delta;
            if (username.equals(player2Username)) return player2Delta;
            return 0;
        }

        public int getNewEloForPlayer(String username) {
            if (username.equals(player1Username)) return player1EloAfter;
            if (username.equals(player2Username)) return player2EloAfter;
            return 0;
        }

        public String getCharacterForPlayer(String username) {
            if (username.equals(player1Username)) return player1Character;
            if (username.equals(player2Username)) return player2Character;
            return null;
        }
    }
}