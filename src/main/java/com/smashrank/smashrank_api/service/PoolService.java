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
    // Key for the Redis Sorted Set
    private static final String POOL_KEY = "smashrank:active_pool";

    public PoolService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Checks a player into the pool.
     * Score = Current Timestamp (used for cleanup).
     * Value = "username:character" (used for search).
     */
    public void checkIn(String username, String character) {
        String value = formatValue(username, character);
        double score = System.currentTimeMillis();
        redisTemplate.opsForZSet().add(POOL_KEY, value, score);
    }

    /**
     * Removes a player from the pool manually.
     */
    public void checkOut(String username, String character) {
        String value = formatValue(username, character);
        redisTemplate.opsForZSet().remove(POOL_KEY, value);
    }

    /**
     * Fast Prefix Search.
     * Finds all players whose usernames start with the query string.
     */
    public Set<PoolPlayer> search(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptySet();
        }

        // Search range: From "Query" to "Query{" (Lexicographically covers all suffixes)
        // Example: "Mew" -> Covers "Mew2King", "MewTwo", etc.
        var range = Range.from(Range.Bound.inclusive(query))
                .to(Range.Bound.exclusive(query + "{"));

        Set<String> results = redisTemplate.opsForZSet().rangeByLex(
                POOL_KEY,
                range,
                RedisZSetCommands.Limit.limit().count(20) // Limit results to 20 for performance
        );

        if (results == null) {
            return Collections.emptySet();
        }

        // Convert "username:character" strings back into PoolPlayer objects
        return results.stream()
                .map(this::parseValue)
                .collect(Collectors.toSet());
    }

    /**
     * The Janitor Task.
     * Runs every 60 seconds.
     * Removes players who haven't updated their timestamp in 15 minutes.
     */
    @Scheduled(fixedRate = 60000)
    public void cleanupInactivePlayers() {
        // Cutoff = Current Time - 15 Minutes (in milliseconds)
        double cutoff = System.currentTimeMillis() - (15 * 60 * 1000);

        // Remove anyone with a score LESS than the cutoff
        Long removedCount = redisTemplate.opsForZSet().removeRangeByScore(POOL_KEY, 0, cutoff);

        if (removedCount != null && removedCount > 0) {
            System.out.println("Janitor: Removed " + removedCount + " inactive players from the pool.");
        }
    }

    // Helper: Combines username and character for storage
    private String formatValue(String username, String character) {
        return username + ":" + character;
    }

    // Helper: Splits storage string back into object
    private PoolPlayer parseValue(String value) {
        String[] parts = value.split(":", 2);
        if (parts.length < 2) {
            return new PoolPlayer(parts[0], "Unknown");
        }
        return new PoolPlayer(parts[0], parts[1]);
    }
}
