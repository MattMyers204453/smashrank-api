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

    // Pending first-reports awaiting confirmation from the other player.
    // Key: matchId, Value: PendingReport (who reported and what they claimed)
    private final Map<String, PendingReport> pendingReports = new ConcurrentHashMap<>();

    public MatchController(SimpMessagingTemplate messagingTemplate, MatchRepository matchRepository) {
        this.messagingTemplate = messagingTemplate;
        this.matchRepository = matchRepository;
    }

    // =========================================================================
    // STEP 1: Send Invite
    // =========================================================================
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

    // =========================================================================
    // STEP 2: Accept Invite
    // =========================================================================
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
        MatchUpdateEvent startEvent = new MatchUpdateEvent(
                match.getId(), "STARTED",
                match.getPlayer1Username(), match.getPlayer2Username(),
                null, null);

        messagingTemplate.convertAndSendToUser(request.challengerUsername(), "/queue/match-updates", startEvent);
        messagingTemplate.convertAndSendToUser(request.opponentUsername(), "/queue/match-updates", startEvent);
    }

    // =========================================================================
    // STEP 3: Decline Invite
    // =========================================================================
    @PostMapping("/decline")
    public void declineInvite(@RequestBody DeclineRequest request) {
        // Release Locks
        playerLocks.remove(request.challengerUsername());
        playerLocks.remove(request.opponentUsername());

        // Notify Challenger
        messagingTemplate.convertAndSendToUser(
                request.challengerUsername(),
                "/queue/match-updates",
                new MatchUpdateEvent(null, "DECLINED",
                        request.challengerUsername(), request.opponentUsername(),
                        null, null)
        );
    }

    // =========================================================================
    // STEP 4: Report Result (first player submits — awaits confirmation)
    // =========================================================================
    @PostMapping("/report")
    public ResponseEntity<String> reportResult(@RequestBody ReportRequest request) {
        // Prevent double-report while already awaiting confirmation
        if (pendingReports.containsKey(request.matchId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("A result has already been submitted. Waiting for confirmation.");
        }

        Match match = matchRepository.findById(request.matchId())
                .orElseThrow(() -> new IllegalArgumentException("Match not found"));

        // Store the pending report — don't finalize yet
        pendingReports.put(request.matchId(),
                new PendingReport(request.reporterUsername(), request.claimedWinner()));

        // Notify BOTH players: reporter knows they're waiting, opponent knows they need to confirm
        MatchUpdateEvent event = new MatchUpdateEvent(
                match.getId(), "AWAITING_CONFIRMATION",
                match.getPlayer1Username(), match.getPlayer2Username(),
                request.reporterUsername(), request.claimedWinner());

        messagingTemplate.convertAndSendToUser(match.getPlayer1Username(), "/queue/match-updates", event);
        messagingTemplate.convertAndSendToUser(match.getPlayer2Username(), "/queue/match-updates", event);

        return ResponseEntity.ok("Result submitted. Awaiting opponent confirmation.");
    }

    // =========================================================================
    // STEP 5: Confirm Result (second player responds — match finalized)
    // =========================================================================
    @PostMapping("/confirm")
    public ResponseEntity<String> confirmResult(@RequestBody ConfirmRequest request) {
        PendingReport pending = pendingReports.get(request.matchId());
        if (pending == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("No pending report found for this match.");
        }

        // Prevent the original reporter from confirming their own report
        if (pending.reporterUsername().equals(request.confirmerUsername())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("You cannot confirm your own report.");
        }

        Match match = matchRepository.findById(request.matchId())
                .orElseThrow(() -> new IllegalArgumentException("Match not found"));

        // Compare: do both players agree on the winner?
        boolean agreed = pending.claimedWinner().equals(request.claimedWinner());

        if (agreed) {
            match.setWinnerUsername(pending.claimedWinner());
            match.setStatus("COMPLETED");
        } else {
            match.setWinnerUsername(null);
            match.setStatus("DISPUTED");
        }
        matchRepository.save(match);

        // Clean up
        pendingReports.remove(request.matchId());
        playerLocks.remove(match.getPlayer1Username());
        playerLocks.remove(match.getPlayer2Username());

        // Notify both players
        String status = agreed ? "ENDED" : "DISPUTED";
        MatchUpdateEvent endEvent = new MatchUpdateEvent(
                match.getId(), status,
                match.getPlayer1Username(), match.getPlayer2Username(),
                null, agreed ? pending.claimedWinner() : null);

        messagingTemplate.convertAndSendToUser(match.getPlayer1Username(), "/queue/match-updates", endEvent);
        messagingTemplate.convertAndSendToUser(match.getPlayer2Username(), "/queue/match-updates", endEvent);

        return ResponseEntity.ok(status);
    }

    // =========================================================================
    // DTOs
    // =========================================================================
    public record InviteRequest(String challengerUsername, String targetUsername) {}
    public record InvitePayload(String inviteId, String from, String status) {}
    public record AcceptRequest(String inviteId, String challengerUsername, String opponentUsername) {}
    public record DeclineRequest(String inviteId, String challengerUsername, String opponentUsername) {}

    // Report: first player submits their claim
    public record ReportRequest(String matchId, String reporterUsername, String claimedWinner) {}

    // Confirm: second player submits their independent view
    public record ConfirmRequest(String matchId, String confirmerUsername, String claimedWinner) {}

    // Internal: held in pendingReports map
    public record PendingReport(String reporterUsername, String claimedWinner) {}

    // WebSocket event — sent on /user/queue/match-updates
    // reporterUsername & claimedWinner are only populated for AWAITING_CONFIRMATION events
    public record MatchUpdateEvent(String matchId, String status,
                                   String player1, String player2,
                                   String reporterUsername, String claimedWinner) {}
}