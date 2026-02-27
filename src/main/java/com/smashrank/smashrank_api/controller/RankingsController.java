package com.smashrank.smashrank_api.controller;

import com.smashrank.smashrank_api.model.Match;
import com.smashrank.smashrank_api.model.Player;
import com.smashrank.smashrank_api.model.PlayerCharacterStats;
import com.smashrank.smashrank_api.repository.MatchRepository;
import com.smashrank.smashrank_api.repository.PlayerCharacterStatsRepository;
import com.smashrank.smashrank_api.repository.PlayerRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class RankingsController {

    private final PlayerRepository playerRepository;
    private final PlayerCharacterStatsRepository charStatsRepository;
    private final MatchRepository matchRepository;

    public RankingsController(PlayerRepository playerRepository,
                              PlayerCharacterStatsRepository charStatsRepository,
                              MatchRepository matchRepository) {
        this.playerRepository = playerRepository;
        this.charStatsRepository = charStatsRepository;
        this.matchRepository = matchRepository;
    }

    // =========================================================================
    // Global Leaderboard (ranked by highest character Elo)
    // =========================================================================

    /**
     * GET /api/rankings?limit=50
     */
    @GetMapping("/rankings")
    public ResponseEntity<GlobalRankingsResponse> getGlobalRankings(
            @RequestParam(defaultValue = "50") int limit) {

        limit = Math.min(limit, 100);
        List<Player> topPlayers = playerRepository.findTopByElo(PageRequest.of(0, limit));
        long totalActive = playerRepository.countActivePlayers();

        List<GlobalRankedPlayerDTO> ranked = new java.util.ArrayList<>();
        for (int i = 0; i < topPlayers.size(); i++) {
            Player p = topPlayers.get(i);

            // Find their best character (most played as tiebreaker)
            List<PlayerCharacterStats> chars = charStatsRepository
                    .findByPlayerIdOrderByEloDesc(p.getId());
            String bestCharacter = chars.isEmpty() ? null : chars.get(0).getCharacterName();

            ranked.add(new GlobalRankedPlayerDTO(
                    i + 1,
                    p.getUsername(),
                    p.getElo(),
                    p.getPeakElo(),
                    p.getWins(),
                    p.getLosses(),
                    p.getTotalGames(),
                    bestCharacter
            ));
        }

        return ResponseEntity.ok(new GlobalRankingsResponse(ranked, totalActive));
    }

    // =========================================================================
    // Per-Character Leaderboard
    // =========================================================================

    /**
     * GET /api/rankings/character/{characterName}?limit=50
     */
    @GetMapping("/rankings/character/{characterName}")
    public ResponseEntity<CharacterRankingsResponse> getCharacterRankings(
            @PathVariable String characterName,
            @RequestParam(defaultValue = "50") int limit) {

        limit = Math.min(limit, 100);
        List<PlayerCharacterStats> topStats = charStatsRepository
                .findTopByCharacter(characterName, PageRequest.of(0, limit));
        long totalActive = charStatsRepository.countActiveByCharacter(characterName);

        List<CharacterRankedPlayerDTO> ranked = new java.util.ArrayList<>();
        for (int i = 0; i < topStats.size(); i++) {
            PlayerCharacterStats s = topStats.get(i);

            // Resolve username from player ID
            String username = playerRepository.findById(s.getPlayerId())
                    .map(Player::getUsername).orElse("Unknown");

            ranked.add(new CharacterRankedPlayerDTO(
                    i + 1,
                    username,
                    s.getElo(),
                    s.getPeakElo(),
                    s.getWins(),
                    s.getLosses(),
                    s.getTotalGames()
            ));
        }

        return ResponseEntity.ok(new CharacterRankingsResponse(
                characterName, ranked, totalActive));
    }

    // =========================================================================
    // Character List (for filter dropdowns)
    // =========================================================================

    /**
     * GET /api/rankings/characters
     * Returns all character names that have been played at least once.
     */
    @GetMapping("/rankings/characters")
    public ResponseEntity<List<String>> getPlayedCharacters() {
        return ResponseEntity.ok(charStatsRepository.findAllPlayedCharacters());
    }

    // =========================================================================
    // Player Profile
    // =========================================================================

    /**
     * GET /api/profile/{username}?matchLimit=20
     */
    @GetMapping("/profile/{username}")
    public ResponseEntity<ProfileResponse> getProfile(
            @PathVariable String username,
            @RequestParam(defaultValue = "20") int matchLimit) {

        Player player = playerRepository.findByUsername(username).orElse(null);
        if (player == null) return ResponseEntity.notFound().build();

        long rank = playerRepository.getPlayerRank(player.getElo());
        long totalActive = playerRepository.countActivePlayers();

        // Character stats breakdown
        List<PlayerCharacterStats> charStats =
                charStatsRepository.findByPlayerIdOrderByEloDesc(player.getId());

        List<CharacterStatDTO> characterBreakdown = charStats.stream().map(s ->
                new CharacterStatDTO(
                        s.getCharacterName(),
                        s.getElo(),
                        s.getPeakElo(),
                        s.getWins(),
                        s.getLosses(),
                        s.getTotalGames(),
                        charStatsRepository.getCharacterRank(s.getCharacterName(), s.getElo())
                )).toList();

        // Main character = most played
        String mainCharacter = null;
        if (!charStats.isEmpty()) {
            List<PlayerCharacterStats> byGames = charStatsRepository
                    .findByPlayerIdOrderByGamesDesc(player.getId(), PageRequest.of(0, 1));
            if (!byGames.isEmpty()) {
                mainCharacter = byGames.get(0).getCharacterName();
            }
        }

        // Recent matches
        matchLimit = Math.min(matchLimit, 50);
        List<Match> recentMatches = matchRepository.findRecentMatchesByUsername(
                username, PageRequest.of(0, matchLimit));

        List<MatchHistoryDTO> history = recentMatches.stream().map(m -> {
            boolean isPlayer1 = m.getPlayer1Username().equals(username);
            String opponent = isPlayer1 ? m.getPlayer2Username() : m.getPlayer1Username();
            boolean won = username.equals(m.getWinnerUsername());
            String myCharacter = isPlayer1 ? m.getPlayer1Character() : m.getPlayer2Character();
            String oppCharacter = isPlayer1 ? m.getPlayer2Character() : m.getPlayer1Character();

            Integer eloBefore = isPlayer1 ? m.getPlayer1EloBefore() : m.getPlayer2EloBefore();
            Integer eloAfter = isPlayer1 ? m.getPlayer1EloAfter() : m.getPlayer2EloAfter();
            Integer eloDelta = (eloBefore != null && eloAfter != null) ? eloAfter - eloBefore : null;

            return new MatchHistoryDTO(
                    m.getId(), opponent, won,
                    myCharacter, oppCharacter,
                    eloBefore, eloAfter, eloDelta,
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
                mainCharacter,
                player.getCreatedAt() != null ? player.getCreatedAt().toString() : null
        );

        return ResponseEntity.ok(new ProfileResponse(profile, characterBreakdown, history));
    }

    // =========================================================================
    // DTOs
    // =========================================================================

    // --- Global Leaderboard ---
    public record GlobalRankingsResponse(List<GlobalRankedPlayerDTO> players, long totalActivePlayers) {}

    public record GlobalRankedPlayerDTO(
            int rank, String username,
            int elo, int peakElo,
            int wins, int losses, int totalGames,
            String bestCharacter  // character with highest Elo
    ) {}

    // --- Per-Character Leaderboard ---
    public record CharacterRankingsResponse(
            String character,
            List<CharacterRankedPlayerDTO> players,
            long totalActivePlayers
    ) {}

    public record CharacterRankedPlayerDTO(
            int rank, String username,
            int elo, int peakElo,
            int wins, int losses, int totalGames
    ) {}

    // --- Profile ---
    public record ProfileResponse(
            PlayerProfileDTO player,
            List<CharacterStatDTO> characters,
            List<MatchHistoryDTO> recentMatches
    ) {}

    public record PlayerProfileDTO(
            String username,
            int elo,          // global (highest character)
            int peakElo,
            int wins,         // aggregate
            int losses,
            int totalGames,
            long rank,        // global rank
            long totalActivePlayers,
            String mainCharacter,  // most-played character
            String memberSince
    ) {}

    public record CharacterStatDTO(
            String character,
            int elo, int peakElo,
            int wins, int losses, int totalGames,
            long characterRank
    ) {}

    public record MatchHistoryDTO(
            String matchId, String opponent, boolean won,
            String myCharacter, String opponentCharacter,
            Integer eloBefore, Integer eloAfter, Integer eloDelta,
            String playedAt
    ) {}
}