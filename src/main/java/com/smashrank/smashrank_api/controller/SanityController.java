package com.smashrank.smashrank_api.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.redis.core.StringRedisTemplate;

@RestController
public class SanityController {

    private final StringRedisTemplate redisTemplate;

    // We inject the Redis template to force a connection attempt
    public SanityController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/sanity")
    public String checkHealth() {
        // 1. Try to write to Redis
        redisTemplate.opsForValue().set("sanity_check", "Connected!");

        // 2. Try to read back from Redis
        String redisResult = redisTemplate.opsForValue().get("sanity_check");

        // 3. Return result to browser
        return "Spring Web is UP. Redis Status: " + redisResult;
    }
}
