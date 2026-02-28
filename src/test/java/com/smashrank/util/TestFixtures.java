package com.smashrank.util;

import com.smashrank.smashrank_api.model.Match;
import com.smashrank.smashrank_api.model.Player;
import com.smashrank.smashrank_api.model.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Factory class for creating test data.
 * Never hardcode usernames or Elo values inline in tests.
 */
public class TestFixtures {

    public static final String DEFAULT_PASSWORD = "password123";
    public static final int DEFAULT_ELO = 1500;

    // =========================================================================
    // User builders
    // =========================================================================

    public static User buildUser(String username) {
        User user = new User(username, DEFAULT_PASSWORD);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    // =========================================================================
    // Player builders
    // =========================================================================

    public static Player buildPlayer(User user, int elo) {
        Player player = new Player(user.getUsername(), user.getUsername());
        player.setUserId(user.getId());
        player.updateElo(elo);
        return player;
    }

    public static Player buildPlayer(User user) {
        return buildPlayer(user, DEFAULT_ELO);
    }

    // =========================================================================
    // Match builders
    // =========================================================================

    /**
     * Build a Match with a generated ID, player UUIDs, and default characters.
     */
    public static Match buildMatch(String player1Username, String player2Username) {
        return buildMatch(player1Username, player2Username, "Fox", "Marth");
    }

    public static Match buildMatch(String player1Username, String player2Username,
                                   String player1Character, String player2Character) {
        UUID p1Id = UUID.randomUUID();
        UUID p2Id = UUID.randomUUID();
        Match match = new Match(player1Username, player2Username, p1Id, p2Id,
                player1Character, player2Character);
        ReflectionTestUtils.setField(match, "id", UUID.randomUUID().toString());
        return match;
    }

    // =========================================================================
    // Isolated pair builders (for load/concurrency tests)
    // =========================================================================

    public record PlayerPair(Player player1, Player player2, User user1, User user2) {}

    /**
     * Generate N non-overlapping player pairs.
     * Each player appears in exactly ONE pair, enabling exact post-image assertions.
     */
    public static List<PlayerPair> buildIsolatedPairs(int count, int startingElo) {
        List<PlayerPair> pairs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            User u1 = buildUser("test_p" + (2 * i + 1));
            User u2 = buildUser("test_p" + (2 * i + 2));
            Player p1 = buildPlayer(u1, startingElo);
            Player p2 = buildPlayer(u2, startingElo);
            pairs.add(new PlayerPair(p1, p2, u1, u2));
        }
        return pairs;
    }
}
