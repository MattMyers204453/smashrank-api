package com.smashrank.smashrank_api.service;

import com.smashrank.smashrank_api.model.PoolPlayer;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PoolService {

    private final StringRedisTemplate redisTemplate;

    // INDEX 1: Sorted by Name (Score always 0)
    private static final String KEY_SEARCH = "smashrank:pool:search";

    // INDEX 2: Sorted by Time (Score is Timestamp)
    private static final String KEY_EXPIRY = "smashrank:pool:expiry";

    public PoolService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void checkIn(String username, String character, int elo) {
        String value = formatValue(username, character, elo);

        // 1. Add to Search Index (Score 0 forces A-Z sorting)
        redisTemplate.opsForZSet().add(KEY_SEARCH, value, 0);

        // 2. Add to Expiry Index (Score = Time)
        redisTemplate.opsForZSet().add(KEY_EXPIRY, value, System.currentTimeMillis());
    }

    public void checkOut(String username, String character, int elo) {
        // formatValue will now generate "mew2king:Mew2King:..." matching the new format
        String value = formatValue(username, character, elo);

        redisTemplate.opsForZSet().remove(KEY_SEARCH, value);
        redisTemplate.opsForZSet().remove(KEY_EXPIRY, value);
    }

    public Set<PoolPlayer> search(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptySet();
        }

        // FORCE LOWERCASE HERE
        String lowerQuery = query.toLowerCase();

        // Search range using the lowercase query
        var range = Range.from(Range.Bound.inclusive(lowerQuery))
                .to(Range.Bound.exclusive(lowerQuery + "{"));

        Set<String> results = redisTemplate.opsForZSet().rangeByLex(
                KEY_SEARCH,
                range,
                RedisZSetCommands.Limit.limit().count(20)
        );

        if (results == null) {
            return Collections.emptySet();
        }

        return results.stream()
                .map(this::parseValue)
                .collect(Collectors.toSet());
    }

//    @Scheduled(fixedRate = 60000) // Run every minute
//    public void cleanupInactivePlayers() {
//        // 15 minute cutoff
//        double cutoff = System.currentTimeMillis() - (15 * 60 * 1000);
//
//        // 1. Find the "Old" players using the EXPIRY index
//        Set<String> expiredPlayers = redisTemplate.opsForZSet()
//                .rangeByScore(KEY_EXPIRY, 0, cutoff);
//
//        if (expiredPlayers != null && !expiredPlayers.isEmpty()) {
//            // 2. Remove them from BOTH indices
//            // (toArray is needed because remove takes a varargs array)
//            String[] players = expiredPlayers.toArray(new String[0]);
//
//            redisTemplate.opsForZSet().remove(KEY_SEARCH, (Object[]) players);
//            redisTemplate.opsForZSet().remove(KEY_EXPIRY, (Object[]) players);
//
//            System.out.println("Janitor: Removed " + players.length + " players.");
//        }
//    }

    // Helper: Prepends lowercase username for sorting
    private String formatValue(String username, String character, int elo) {
        // Format: "lowercase:Original:Character:ELO"
        return username.toLowerCase() + ":" + username + ":" + character + ":" + elo;
    }

    // Helper: Ignores the lowercase prefix, reads the rest
    private PoolPlayer parseValue(String value) {
        String[] parts = value.split(":");

        // We expect 4 parts: [0]lower, [1]Original, [2]Char, [3]Elo
        if (parts.length < 4) {
            return new PoolPlayer("Unknown", "Unknown", 1000);
        }

        // Return parts[1] (Original Name) instead of parts[0]
        return new PoolPlayer(parts[1], parts[2], Integer.parseInt(parts[3]));
    }

    public void flushPool() {
        redisTemplate.delete("smashrank:pool:search");
        redisTemplate.delete("smashrank:pool:expiry");

        System.out.println("⚠️ ADMIN ACTION: Pool flushed manually.");
    }
}
