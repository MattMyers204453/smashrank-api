package com.smashrank.smashrank_api.repository;

import com.smashrank.smashrank_api.model.Match;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchRepository extends JpaRepository<Match, String> {
}