package com.smashrank.smashrank_api.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "matches")
public class Match {

    // Getters
    @Getter
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Getter
    @Column(nullable = false)
    private String player1Username; // Challenger

    @Getter
    @Column(nullable = false)
    private String player2Username; // Opponent

    @Getter
    @Setter
    private String winnerUsername;

    // ACTIVE → COMPLETED or DISPUTED
    @Getter
    @Setter
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

}