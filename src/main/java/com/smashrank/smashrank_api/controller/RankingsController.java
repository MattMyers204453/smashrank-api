package com.smashrank.smashrank_api.controller;

import com.smashrank.smashrank_api.model.Match;
import com.smashrank.smashrank_api.model.Player;
import com.smashrank.smashrank_api.repository.MatchRepository;
import com.smashrank.smashrank_api.repository.PlayerRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST endpoints for leaderboard and player profiles.
 * These are read-only and don't require WebSocket — pure HTTP.
 */
@RestController
@RequestMapping("/api")
public class RankingsController {

    private final PlayerRepository playerRepository;
    private final MatchRepository matchRepository;

    public RankingsController(PlayerRepository playerRepository, MatchRepository matchRepository) {
        this.playerRepository = playerRepository;
        this.matchRepository = matchRepository;
    }

    // =========================================================================
    // Leaderboard
    // =========================================================================

    /**
     * GET /api/rankings?limit=50
     * Returns the top players by Elo, ordered descending.
     */
    @GetMapping("/rankings")
    public ResponseEntity<RankingsResponse> getRankings(
            @RequestParam(defaultValue = "50") int limit) {

        limit = Math.min(limit, 100); // Cap at 100

        List<Player> topPlayers = playerRepository.findTopByElo(PageRequest.of(0, limit));
        long totalActive = playerRepository.countActivePlayers();

        List<RankedPlayerDTO> ranked = new java.util.ArrayList<>();
        for (int i = 0; i < topPlayers.size(); i++) {
            Player p = topPlayers.get(i);
            ranked.add(new RankedPlayerDTO(
                    i + 1,  // rank (1-indexed)
                    p.getUsername(),
                    p.getElo(),
                    p.getPeakElo(),
                    p.getWins(),
                    p.getLosses(),
                    p.getTotalGames()
            ));
        }

        return ResponseEntity.ok(new RankingsResponse(ranked, totalActive));
    }

    // =========================================================================
    // Player Profile
    // =========================================================================

    /**
     * GET /api/profile/{username}
     * Returns a player's stats and recent match history.
     */
    @GetMapping("/profile/{username}")
    public ResponseEntity<ProfileResponse> getProfile(
            @PathVariable String username,
            @RequestParam(defaultValue = "20") int matchLimit) {

        Player player = playerRepository.findByUsername(username)
                .orElse(null);

        if (player == null) {
            return ResponseEntity.notFound().build();
        }

        long rank = playerRepository.getPlayerRank(player.getElo());
        long totalActive = playerRepository.countActivePlayers();

        // Recent matches
        matchLimit = Math.min(matchLimit, 50);
        List<Match> recentMatches = matchRepository.findRecentMatchesByUsername(
                username, PageRequest.of(0, matchLimit));

        List<MatchHistoryDTO> history = recentMatches.stream().map(m -> {
            boolean isPlayer1 = m.getPlayer1Username().equals(username);
            String opponent = isPlayer1 ? m.getPlayer2Username() : m.getPlayer1Username();
            boolean won = username.equals(m.getWinnerUsername());

            Integer eloBefore = isPlayer1 ? m.getPlayer1EloBefore() : m.getPlayer2EloBefore();
            Integer eloAfter = isPlayer1 ? m.getPlayer1EloAfter() : m.getPlayer2EloAfter();
            Integer eloDelta = (eloBefore != null && eloAfter != null) ? eloAfter - eloBefore : null;

            return new MatchHistoryDTO(
                    m.getId(),
                    opponent,
                    won,
                    eloBefore,
                    eloAfter,
                    eloDelta,
                    m.getPlayedAt() != null ? m.getPlayedAt().toString() : null
            );
        }).toList();

        PlayerProfileDTO profile = new PlayerProfileDTO(
                player.getUsername(),
                player.getElo(),
                player.getPeakElo(),
                player.getWins(),
                player.getLosses(),
                player.getTotalGames(),
                rank,
                totalActive,
                player.getCreatedAt() != null ? player.getCreatedAt().toString() : null
        );

        return ResponseEntity.ok(new ProfileResponse(profile, history));
    }

    // =========================================================================
    // DTOs
    // =========================================================================

    public record RankingsResponse(List<RankedPlayerDTO> players, long totalActivePlayers) {}

    public record RankedPlayerDTO(
            int rank,
            String username,
            int elo,
            int peakElo,
            int wins,
            int losses,
            int totalGames
    ) {}

    public record ProfileResponse(PlayerProfileDTO player, List<MatchHistoryDTO> recentMatches) {}

    public record PlayerProfileDTO(
            String username,
            int elo,
            int peakElo,
            int wins,
            int losses,
            int totalGames,
            long rank,
            long totalActivePlayers,
            String memberSince
    ) {}

    public record MatchHistoryDTO(
            String matchId,
            String opponent,
            boolean won,
            Integer eloBefore,
            Integer eloAfter,
            Integer eloDelta,
            String playedAt
    ) {}
}