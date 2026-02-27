package com.smashrank.smashrank_api.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "matches")
public class Match {

    @Getter
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    // =========================================================================
    // Player identification (username + UUID)
    // =========================================================================
    @Getter
    @Column(nullable = false)
    private String player1Username;

    @Getter
    @Column(nullable = false)
    private String player2Username;

    @Getter @Setter
    private String winnerUsername;

    @Getter @Setter
    @Column(name = "player1_id")
    private UUID player1Id;

    @Getter @Setter
    @Column(name = "player2_id")
    private UUID player2Id;

    @Getter @Setter
    @Column(name = "winner_id")
    private UUID winnerId;

    // =========================================================================
    // Character played (set at match creation from pool check-in data)
    // =========================================================================
    @Getter @Setter
    @Column(name = "player1_character", length = 50)
    private String player1Character;

    @Getter @Setter
    @Column(name = "player2_character", length = 50)
    private String player2Character;

    // =========================================================================
    // Match status
    // =========================================================================
    @Getter @Setter
    @Column(nullable = false)
    private String status = "ACTIVE";

    @Getter
    @CreationTimestamp
    private LocalDateTime playedAt;

    // =========================================================================
    // Elo audit trail (populated on COMPLETED, null for ACTIVE/DISPUTED)
    // These are character-specific Elo values.
    // =========================================================================
    @Getter @Setter
    @Column(name = "player1_elo_before")
    private Integer player1EloBefore;

    @Getter @Setter
    @Column(name = "player1_elo_after")
    private Integer player1EloAfter;

    @Getter @Setter
    @Column(name = "player2_elo_before")
    private Integer player2EloBefore;

    @Getter @Setter
    @Column(name = "player2_elo_after")
    private Integer player2EloAfter;

    @Getter @Setter
    @Column(name = "player1_k_factor")
    private Integer player1KFactor;

    @Getter @Setter
    @Column(name = "player2_k_factor")
    private Integer player2KFactor;

    // Constructors
    public Match() {}

    public Match(String player1Username, String player2Username) {
        this.player1Username = player1Username;
        this.player2Username = player2Username;
    }

    public Match(String player1Username, String player2Username,
                 UUID player1Id, UUID player2Id) {
        this.player1Username = player1Username;
        this.player2Username = player2Username;
        this.player1Id = player1Id;
        this.player2Id = player2Id;
    }

    /**
     * Full constructor with characters (used for new match creation).
     */
    public Match(String player1Username, String player2Username,
                 UUID player1Id, UUID player2Id,
                 String player1Character, String player2Character) {
        this.player1Username = player1Username;
        this.player2Username = player2Username;
        this.player1Id = player1Id;
        this.player2Id = player2Id;
        this.player1Character = player1Character;
        this.player2Character = player2Character;
    }

    // Elo delta helpers
    public Integer getPlayer1EloDelta() {
        if (player1EloBefore == null || player1EloAfter == null) return null;
        return player1EloAfter - player1EloBefore;
    }

    public Integer getPlayer2EloDelta() {
        if (player2EloBefore == null || player2EloAfter == null) return null;
        return player2EloAfter - player2EloBefore;
    }
}