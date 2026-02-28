package com.smashrank.util;

/**
 * Test-scoped mirror of the production EloCalculator.
 * Used to compute expected post-images before running tests.
 *
 * IMPORTANT: Must exactly match the production implementation in
 * com.smashrank.smashrank_api.service.EloCalculator.
 * If production logic changes, update this too.
 */
public class EloCalculator {

    private static final int RATING_FLOOR = 100;
    private static final int DEFAULT_RATING = 1200;

    /**
     * K-factor based on total games played (per-character).
     * Provisional (< 30 games): K=40
     * Intermediate (30-99 games): K=20
     * Established (100+ games): K=10
     */
    public static int kFactor(int totalGames) {
        if (totalGames < 30) return 40;
        if (totalGames < 100) return 20;
        return 10;
    }

    /**
     * Expected score (win probability) for player A against player B.
     */
    public static double expectedScore(int ratingA, int ratingB) {
        return 1.0 / (1.0 + Math.pow(10.0, (ratingB - ratingA) / 400.0));
    }

    /**
     * Calculate the Elo delta from player A's perspective.
     */
    public static int delta(int ratingA, int ratingB, boolean won, int totalGames) {
        double expected = expectedScore(ratingA, ratingB);
        double actual = won ? 1.0 : 0.0;
        int k = kFactor(totalGames);
        int newRating = (int) Math.round(ratingA + k * (actual - expected));
        newRating = Math.max(RATING_FLOOR, newRating);
        return newRating - ratingA;
    }

    public static int getDefaultRating() {
        return DEFAULT_RATING;
    }

    public static int getRatingFloor() {
        return RATING_FLOOR;
    }
}
