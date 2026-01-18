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

    public void checkIn(String username, String character) {
        String value = formatValue(username, character);

        // 1. Add to Search Index (Score 0 forces A-Z sorting)
        redisTemplate.opsForZSet().add(KEY_SEARCH, value, 0);

        // 2. Add to Expiry Index (Score = Time)
        redisTemplate.opsForZSet().add(KEY_EXPIRY, value, System.currentTimeMillis());
    }

    public void checkOut(String username, String character) {
        String value = formatValue(username, character);

        // Remove from BOTH indices
        redisTemplate.opsForZSet().remove(KEY_SEARCH, value);
        redisTemplate.opsForZSet().remove(KEY_EXPIRY, value);
    }

    public Set<PoolPlayer> search(String query) {
        if (query == null || query.isBlank()) return Collections.emptySet();

        // Search the SEARCH index (which is guaranteed to be A-Z)
        var range = Range.from(Range.Bound.inclusive(query))
                .to(Range.Bound.exclusive(query + "{"));

        Set<String> results = redisTemplate.opsForZSet().rangeByLex(
                KEY_SEARCH,
                range,
                RedisZSetCommands.Limit.limit().count(20)
        );

        if (results == null) return Collections.emptySet();

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

    private String formatValue(String username, String character) {
        return username + ":" + character;
    }

    private PoolPlayer parseValue(String value) {
        String[] parts = value.split(":", 2);
        return (parts.length < 2)
                ? new PoolPlayer(parts[0], "Unknown")
                : new PoolPlayer(parts[0], parts[1]);
    }
}
