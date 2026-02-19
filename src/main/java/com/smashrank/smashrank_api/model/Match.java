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
    // Username columns (existing — still used by STOMP messaging, Flutter, etc.)
    // These will be removed in Phase 5 after the full migration.
    // =========================================================================
    @Getter
    @Column(nullable = false)
    private String player1Username; // Challenger

    @Getter
    @Column(nullable = false)
    private String player2Username; // Opponent

    @Getter @Setter
    private String winnerUsername;

    // =========================================================================
    // UUID columns (Phase 3 — the future primary identifiers)
    // Nullable for now so existing rows aren't broken by the migration.
    // =========================================================================
    @Getter @Setter
    @Column(name = "player1_id")
    private UUID player1Id;

    @Getter @Setter
    @Column(name = "player2_id")
    private UUID player2Id;

    @Getter @Setter
    @Column(name = "winner_id")
    private UUID winnerId;

    // ACTIVE → COMPLETED or DISPUTED
    @Getter @Setter
    @Column(nullable = false)
    private String status = "ACTIVE";

    @CreationTimestamp
    private LocalDateTime playedAt;

    // Constructors
    public Match() {}

    public Match(String player1Username, String player2Username) {
        this.player1Username = player1Username;
        this.player2Username = player2Username;
    }

    /**
     * Phase 3: Set both username and UUID fields at creation time.
     */
    public Match(String player1Username, String player2Username,
                 UUID player1Id, UUID player2Id) {
        this.player1Username = player1Username;
        this.player2Username = player2Username;
        this.player1Id = player1Id;
        this.player2Id = player2Id;
    }
}