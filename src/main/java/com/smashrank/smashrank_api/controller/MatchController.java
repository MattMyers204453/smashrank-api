package com.smashrank.smashrank_api.controller;

import com.smashrank.smashrank_api.model.Match;
import com.smashrank.smashrank_api.repository.MatchRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/matches")
@CrossOrigin(origins = "*")
public class MatchController {

    private final SimpMessagingTemplate messagingTemplate;
    private final MatchRepository matchRepository;

    // MVP Lock Map: Prevents race conditions.
    // Key: Username, Value: InviteID
    private final Map<String, String> playerLocks = new ConcurrentHashMap<>();

    public MatchController(SimpMessagingTemplate messagingTemplate, MatchRepository matchRepository) {
        this.messagingTemplate = messagingTemplate;
        this.matchRepository = matchRepository;
    }

    /**
     * STEP 1: Send Invite
     */
    @PostMapping("/invite")
    public ResponseEntity<String> sendInvite(@RequestBody InviteRequest request) {
        String challenger = request.challengerUsername();
        String opponent = request.targetUsername();

        // 1. Race Condition Check
        if (playerLocks.containsKey(challenger)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("You already have a pending invite.");
        }
        if (playerLocks.containsKey(opponent)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Player is busy (likely sending you an invite!)");
        }

        // 2. Lock players
        String inviteId = UUID.randomUUID().toString();
        playerLocks.put(challenger, inviteId);
        playerLocks.put(opponent, inviteId);

        // 3. Send WebSocket Message to Opponent
        InvitePayload payload = new InvitePayload(inviteId, challenger, "PENDING");
        messagingTemplate.convertAndSendToUser(opponent, "/queue/invites", payload);

        return ResponseEntity.ok(inviteId);
    }

    /**
     * STEP 2: Accept Invite
     */
    @PostMapping("/accept")
    public void acceptInvite(@RequestBody AcceptRequest request) {
        String inviteId = request.inviteId();

        // Security: Ensure the lock matches the invite
        String lockedId = playerLocks.get(request.challengerUsername());
        if (lockedId == null || !lockedId.equals(inviteId)) {
            throw new IllegalStateException("Invite expired or invalid");
        }

        // Create the official match record
        Match match = new Match(request.challengerUsername(), request.opponentUsername());
        matchRepository.save(match);

        // Notify BOTH players
        MatchUpdateEvent startEvent = new MatchUpdateEvent(match.getId(), "STARTED", match.getPlayer1Username(), match.getPlayer2Username());

        messagingTemplate.convertAndSendToUser(request.challengerUsername(), "/queue/match-updates", startEvent);
        messagingTemplate.convertAndSendToUser(request.opponentUsername(), "/queue/match-updates", startEvent);
    }

    /**
     * STEP 3: Decline Invite
     */
    @PostMapping("/decline")
    public void declineInvite(@RequestBody DeclineRequest request) {
        // Release Locks
        playerLocks.remove(request.challengerUsername());
        playerLocks.remove(request.opponentUsername());

        // Notify Challenger
        messagingTemplate.convertAndSendToUser(
                request.challengerUsername(),
                "/queue/match-updates",
                new MatchUpdateEvent(null, "DECLINED", request.challengerUsername(), request.opponentUsername())
        );
    }

    /**
     * STEP 4: Report Result
     * Ends the match and notifies both players.
     */
    @PostMapping("/report")
    public void reportResult(@RequestBody ReportRequest request) {
        Match match = matchRepository.findById(request.matchId())
                .orElseThrow(() -> new IllegalArgumentException("Match not found"));

        // Update the winner
        match.setWinnerUsername(request.winnerUsername());
        matchRepository.save(match);

        // Release locks — match is over, both players can challenge again
        playerLocks.remove(match.getPlayer1Username());
        playerLocks.remove(match.getPlayer2Username());

        // Notify BOTH players that the match is over
        MatchUpdateEvent endEvent = new MatchUpdateEvent(
                match.getId(),
                "ENDED",
                match.getPlayer1Username(),
                match.getPlayer2Username()
        );

        messagingTemplate.convertAndSendToUser(match.getPlayer1Username(), "/queue/match-updates", endEvent);
        messagingTemplate.convertAndSendToUser(match.getPlayer2Username(), "/queue/match-updates", endEvent);
    }

    // Add this Record at the bottom with the others
    public record ReportRequest(String matchId, String winnerUsername) {}

    // --- DTOs ---
    public record InviteRequest(String challengerUsername, String targetUsername) {}
    public record InvitePayload(String inviteId, String from, String status) {}
    public record AcceptRequest(String inviteId, String challengerUsername, String opponentUsername) {}
    public record DeclineRequest(String inviteId, String challengerUsername, String opponentUsername) {}
    public record MatchUpdateEvent(String matchId, String status, String player1, String player2) {}
}