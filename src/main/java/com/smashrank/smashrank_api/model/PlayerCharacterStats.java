package com.smashrank.smashrank_api.model;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Per-character Elo stats. One row per player per character they've played.
 * This is the source of truth for character-specific ratings.
 *
 * The players.elo field is denormalized as the MAX of all character Elos
 * for global leaderboard queries.
 */
@Getter
@Entity
@Table(name = "player_character_stats",
        uniqueConstraints = @UniqueConstraint(columnNames = {"player_id", "character_name"}))
public class PlayerCharacterStats {

    // Getters
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "character_name", nullable = false, length = 50)
    private String characterName;

    @Column(nullable = false)
    private int elo = 1200;

    @Column(name = "peak_elo", nullable = false)
    private int peakElo = 1200;

    @Column(nullable = false)
    private int wins = 0;

    @Column(nullable = false)
    private int losses = 0;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    // Constructors
    public PlayerCharacterStats() {}

    public PlayerCharacterStats(Long playerId, String characterName) {
        this.playerId = playerId;
        this.characterName = characterName;
    }

    // Total games
    public int getTotalGames() { return wins + losses; }

    // Elo helpers
    public void updateElo(int newElo) {
        this.elo = newElo;
        if (newElo > this.peakElo) {
            this.peakElo = newElo;
        }
    }

    public void recordWin() { this.wins++; }
    public void recordLoss() { this.losses++; }
}