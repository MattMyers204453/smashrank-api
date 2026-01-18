package com.smashrank.smashrank_api.controller;

import com.smashrank.smashrank_api.model.PoolPlayer;
import com.smashrank.smashrank_api.service.PoolService;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/pool")
public class PoolController {

    private final PoolService poolService;

    public PoolController(PoolService poolService) {
        this.poolService = poolService;
    }

    @PostMapping("/check-in")
    public void checkIn(@RequestParam String username, @RequestParam String character) {
        poolService.checkIn(username, character);
    }

    @PostMapping("/check-out")
    public void checkOut(@RequestParam String username, @RequestParam String character) {
        poolService.checkOut(username, character);
    }

    @GetMapping("/search")
    public Set<PoolPlayer> search(@RequestParam String query) {
        return poolService.search(query);
    }
}
