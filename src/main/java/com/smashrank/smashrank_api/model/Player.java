package com.smashrank.smashrank_api.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Player gaming profile. The `elo` field is denormalized — it always equals
 * the player's highest character Elo, updated by EloService after each match.
 * This enables fast global leaderboard queries without joining character stats.
 *
 * The `wins` and `losses` fields are aggregate totals across all characters.
 */
@Entity
@Table(name = "players")
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", unique = true)
    private UUID userId;

    @Column(nullable = false, unique = true)
    private String username;

    private String lastTag;

    /** Denormalized: MAX Elo across all characters. Updated by EloService. */
    @Column(nullable = false)
    private int elo = 1200;

    /** Denormalized: highest Elo this player has ever achieved on any character. */
    private int peakElo = 1200;

    /** Aggregate wins across all characters. */
    private int wins = 0;

    /** Aggregate losses across all characters. */
    private int losses = 0;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public Player() {}
    public Player(String username, String lastTag) {
        this.username = username;
        this.lastTag = lastTag;
    }

    // Getters
    public Long getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getLastTag() { return lastTag; }
    public int getElo() { return elo; }
    public int getPeakElo() { return peakElo; }
    public int getWins() { return wins; }
    public int getLosses() { return losses; }
    public int getTotalGames() { return wins + losses; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // Setters
    public void setUserId(UUID userId) { this.userId = userId; }
    public void setUsername(String username) { this.username = username; }
    public void setLastTag(String lastTag) { this.lastTag = lastTag; }

    public void updateElo(int newElo) {
        this.elo = newElo;
        if (newElo > this.peakElo) {
            this.peakElo = newElo;
        }
    }

    public void recordWin() { this.wins++; }
    public void recordLoss() { this.losses++; }
}