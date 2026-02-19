package com.smashrank.smashrank_api.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "players")
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // =========================================================================
    // NEW (Phase 2): Links this gaming profile to the auth identity.
    // Nullable for now so existing rows don't break. Phase 5 will enforce this.
    // =========================================================================
    @Column(name = "user_id", unique = true)
    private UUID userId;

    // Still here for now — existing match flow, pool, and WebSockets use it.
    // Will be removed in Phase 5 after everything migrates to userId.
    @Column(nullable = false, unique = true)
    private String username;

    // The cosmetic name (e.g., "CT | M2K")
    private String lastTag;

    @Column(nullable = false)
    private int elo = 1200;

    private int peakElo = 1200;

    private int wins = 0;
    private int losses = 0;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    // Constructors
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
}