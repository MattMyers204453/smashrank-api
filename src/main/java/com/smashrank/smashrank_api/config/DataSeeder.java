package com.smashrank.smashrank_api.config;

import com.smashrank.smashrank_api.model.Player;
import com.smashrank.smashrank_api.model.User;
import com.smashrank.smashrank_api.repository.PlayerRepository;
import com.smashrank.smashrank_api.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PlayerRepository playerRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(UserRepository userRepository,
                      PlayerRepository playerRepository,
                      PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.playerRepository = playerRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) throws Exception {
        // Check if data already exists to prevent duplicates on restart
        if (userRepository.count() == 0) {
            seedUsersAndPlayers();
        }
    }

    private void seedUsersAndPlayers() {
        // All seed accounts share the password "password123" for dev/testing.
        String hashedPassword = passwordEncoder.encode("password123");

        createUserAndPlayer("mew2king", hashedPassword, 2000);
        createUserAndPlayer("mang0", hashedPassword, 2100);
        createUserAndPlayer("zain", hashedPassword, 2200);
        createUserAndPlayer("ibdw", hashedPassword, 1200);

        System.out.println("Database seeded with 4 users and players.");
    }

    private void createUserAndPlayer(String username, String hashedPassword, int elo) {
        // 1. Create User (auth identity)
        User user = new User(username, hashedPassword);
        userRepository.save(user);

        // 2. Create Player (gaming profile) linked via userId
        Player player = new Player(username, username);
        player.setUserId(user.getId());
        player.updateElo(elo);
        playerRepository.save(player);
    }
}