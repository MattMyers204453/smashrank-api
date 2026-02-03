package com.smashrank.smashrank_api.config;

import com.smashrank.smashrank_api.model.Player;
import com.smashrank.smashrank_api.repository.PlayerRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class DataSeeder implements CommandLineRunner {

    private final PlayerRepository playerRepository;

    public DataSeeder(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        // Check if data already exists to prevent duplicates on restart
        if (playerRepository.count() == 0) {
            seedPlayers();
        }
    }

    private void seedPlayers() {
        // Using the constructor: public Player(String username, String lastTag)
        Player p1 = new Player("mew2king", "CT | M2K");
        p1.updateElo(2000); // Manually setting ELO using your helper method
        // Note: wins/losses will default to 0 unless you add setters for them

        Player p2 = new Player("mang0", "C9 | Mang0");
        p2.updateElo(2100);

        Player p3 = new Player("zain", "GG | Zain");
        p3.updateElo(2200);

        Player p4 = new Player("ibdw", "Cody Schwab");
        // ELO defaults to 1200 as per your Player.java definition

        List<Player> players = Arrays.asList(p1, p2, p3, p4);

        playerRepository.saveAll(players);

        System.out.println("Database seeded with " + players.size() + " players.");
    }
}