package com.smashrank.smashrank_api.controller;

import com.smashrank.smashrank_api.model.Player;
import com.smashrank.smashrank_api.model.PlayerCharacterStats;
import com.smashrank.smashrank_api.model.PoolPlayer;
import com.smashrank.smashrank_api.repository.PlayerCharacterStatsRepository;
import com.smashrank.smashrank_api.repository.PlayerRepository;
import com.smashrank.smashrank_api.service.PoolService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/pool")
@CrossOrigin(origins = "*")
public class PoolController {

    private final PoolService poolService;
    private final PlayerRepository playerRepository;
    private final PlayerCharacterStatsRepository charStatsRepository;

    public PoolController(PoolService poolService, PlayerRepository playerRepository, PlayerCharacterStatsRepository charStatsRepository) {
        this.poolService = poolService;
        this.playerRepository = playerRepository;
        this.charStatsRepository = charStatsRepository;
    }

    @PostMapping("/check-in")
    public ResponseEntity<String> checkIn(
            @RequestParam String username,
            @RequestParam String character,
            @RequestParam(required = false) Integer elo) {  // elo param becomes optional/ignored

        // Look up the player's character-specific Elo from the database
        Player player = playerRepository.findByUsername(username).orElse(null);
        if (player == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Player not found.");
        }

        int characterElo = charStatsRepository
                .findByPlayerIdAndCharacterName(player.getId(), character)
                .map(PlayerCharacterStats::getElo)
                .orElse(1200);  // Default for first time playing this character

        // Check in with the character-specific Elo
        poolService.checkIn(username, character, characterElo);

        return ResponseEntity.ok("Checked in as " + character + " (" + characterElo + " Elo)");
    }

    @PostMapping("/check-out")
    public void checkOut(@RequestParam String username) {
        poolService.checkOut(username);
    }

    @GetMapping("/search")
    public Set<PoolPlayer> search(@RequestParam String query) {
        return poolService.search(query);
    }

    @DeleteMapping("/admin/flush")
    public void flushPool() { poolService.flushPool(); }

    @PostMapping("/admin/seed")
    public void seedPool(@RequestBody List<PoolPlayer> players) { poolService.bulkCheckIn(players); }

    @GetMapping("/all")
    public Set<PoolPlayer> getAllPlayers() {
        return poolService.findAll();
    }
}
