package com.smashrank.smashrank_api.service;

/**
 * Pure Elo calculation utility. Stateless — no dependencies.
 *
 * Formula:
 *   Expected score:  E = 1 / (1 + 10^((opponentElo - playerElo) / 400))
 *   New rating:      R' = R + K * (S - E)
 *
 * K-factor strategy (dynamic, three-tier):
 *   - K=40 for provisional players (< 30 games)
 *   - K=20 for established players (30–99 games)
 *   - K=10 for veterans (100+ games)
 *
 * Rating floor: 100 (no player can drop below this).
 */
public class EloCalculator {

    private static final int RATING_FLOOR = 100;
    private static final int DEFAULT_RATING = 1200;

    // =========================================================================
    // K-Factor
    // =========================================================================

    /**
     * Dynamic K-factor based on total games played.
     * Higher K = ratings move faster (good for new players finding their level).
     */
    public static int getKFactor(int totalGames) {
        if (totalGames < 30) return 40;   // Provisional
        if (totalGames < 100) return 20;  // Established
        return 10;                         // Veteran
    }

    // =========================================================================
    // Expected Score
    // =========================================================================

    /**
     * Expected score (probability of winning) for playerA against playerB.
     * Returns a value between 0.0 and 1.0.
     */
    public static double expectedScore(int playerElo, int opponentElo) {
        return 1.0 / (1.0 + Math.pow(10.0, (opponentElo - playerElo) / 400.0));
    }

    // =========================================================================
    // Rating Calculation
    // =========================================================================

    /**
     * Calculate new Elo rating after a match.
     *
     * @param currentElo   Player's current rating
     * @param opponentElo  Opponent's current rating
     * @param won          true if this player won, false if lost
     * @param totalGames   Player's total completed games (for K-factor)
     * @return new Elo rating (floored at RATING_FLOOR)
     */
    public static int calculateNewRating(int currentElo, int opponentElo, boolean won, int totalGames) {
        double expected = expectedScore(currentElo, opponentElo);
        double actual = won ? 1.0 : 0.0;
        int k = getKFactor(totalGames);

        int newRating = (int) Math.round(currentElo + k * (actual - expected));
        return Math.max(RATING_FLOOR, newRating);
    }

    /**
     * Calculate the Elo delta (change) without applying it.
     * Useful for previewing or auditing.
     */
    public static int calculateDelta(int currentElo, int opponentElo, boolean won, int totalGames) {
        return calculateNewRating(currentElo, opponentElo, won, totalGames) - currentElo;
    }

    public static int getDefaultRating() {
        return DEFAULT_RATING;
    }

    public static int getRatingFloor() {
        return RATING_FLOOR;
    }
}