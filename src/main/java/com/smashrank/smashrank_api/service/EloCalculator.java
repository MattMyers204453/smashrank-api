package com.smashrank.smashrank_api.service;

/**
 * Pure Elo calculation utility. Stateless — no dependencies.
 *
 * K-factor is per-character: a player provisional with Mario (K=40)
 * can simultaneously be a veteran with Fox (K=10).
 *
 * Rating floor: 100.
 */
public class EloCalculator {

    private static final int RATING_FLOOR = 100;
    private static final int DEFAULT_RATING = 1200;

    public static int getKFactor(int totalGames) {
        if (totalGames < 30) return 40;
        if (totalGames < 100) return 20;
        return 10;
    }

    public static double expectedScore(int playerElo, int opponentElo) {
        return 1.0 / (1.0 + Math.pow(10.0, (opponentElo - playerElo) / 400.0));
    }

    public static int calculateNewRating(int currentElo, int opponentElo, boolean won, int totalGames) {
        double expected = expectedScore(currentElo, opponentElo);
        double actual = won ? 1.0 : 0.0;
        int k = getKFactor(totalGames);
        int newRating = (int) Math.round(currentElo + k * (actual - expected));
        return Math.max(RATING_FLOOR, newRating);
    }

    public static int calculateDelta(int currentElo, int opponentElo, boolean won, int totalGames) {
        return calculateNewRating(currentElo, opponentElo, won, totalGames) - currentElo;
    }

    public static int getDefaultRating() { return DEFAULT_RATING; }
    public static int getRatingFloor() { return RATING_FLOOR; }
}