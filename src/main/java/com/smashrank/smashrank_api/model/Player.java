package com.smashrank.smashrank_api.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "players")
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The stable, unique ID (e.g., "mew2king")
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

    // Getters and Setters for everything...
    public void updateElo(int newElo) {
        this.elo = newElo;
        if (newElo > this.peakElo) {
            this.peakElo = newElo;
        }
    }
}
