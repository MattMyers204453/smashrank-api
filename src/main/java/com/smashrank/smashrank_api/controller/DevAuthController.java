package com.smashrank.smashrank_api.controller;

import com.smashrank.smashrank_api.model.Player;
import com.smashrank.smashrank_api.repository.PlayerRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dev/auth")
@CrossOrigin(origins = "*")
public class DevAuthController {

    private final PlayerRepository playerRepository;

    public DevAuthController(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    @PostMapping("/login")
    public Player login(@RequestParam String username) {
        // Find existing or create new (The "Turnstile")
        return playerRepository.findByUsername(username)
                .orElseGet(() -> playerRepository.save(new Player(username, username)));
    }
}