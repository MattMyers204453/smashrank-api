package com.smashrank.smashrank_api.controller;

import com.smashrank.smashrank_api.model.Match;
import com.smashrank.smashrank_api.repository.MatchRepository;
import com.smashrank.smashrank_api.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/matches")
@CrossOrigin(origins = "*")
public class MatchController {

    private final SimpMessagingTemplate messagingTemplate;
    private final MatchRepository matchRepository;
    private final UserService userService;

    // MVP Lock Map: Prevents race conditions.
    // Key: Username, Value: InviteID
    // (Still keyed by username — STOMP routing uses username as Principal)
    private final Map<String, String> playerLocks = new ConcurrentHashMap<>();

    // Pending first-reports awaiting confirmation from the other player.
    // Key: matchId, Value: PendingReport (who reported and what they claimed)
    private final Map<String, PendingReport> pendingReports = new ConcurrentHashMap<>();

    // Pending rematch offers awaiting both players' responses.
    // Key: matchId (of the COMPLETED match), Value: PendingRematch
    private final Map<String, PendingRematch> pendingRematches = new ConcurrentHashMap<>();

    public MatchController(SimpMessagingTemplate messagingTemplate,
                           MatchRepository matchRepository,
                           UserService userService) {
        this.messagingTemplate = messagingTemplate;
        this.matchRepository = matchRepository;
        this.userService = userService;
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

        // Phase 3: Resolve UUIDs for both players
        UUID challengerId = userService.getUserIdByUsername(request.challengerUsername());
        UUID opponentId = userService.getUserIdByUsername(request.opponentUsername());

        // Create the official match record (with both username and UUID fields)
        Match match = new Match(
                request.challengerUsername(), request.opponentUsername(),
                challengerId, opponentId);
        matchRepository.save(match);

        // Notify both players
        MatchUpdateEvent event = new MatchUpdateEvent(
                match.getId(), "STARTED",
                match.getPlayer1Username(), match.getPlayer2Username(),
                null, null, null);

        messagingTemplate.convertAndSendToUser(request.challengerUsername(), "/queue/match-updates", event);
        messagingTemplate.convertAndSendToUser(request.opponentUsername(), "/queue/match-updates", event);
    }

    // =========================================================================
    // STEP 2b: Decline Invite
    // =========================================================================
    @PostMapping("/decline")
    public void declineInvite(@RequestBody DeclineRequest request) {
        // Release locks
        playerLocks.remove(request.challengerUsername());
        playerLocks.remove(request.opponentUsername());

        // Notify challenger
        MatchUpdateEvent event = new MatchUpdateEvent(
                null, "DECLINED",
                request.challengerUsername(), request.opponentUsername(),
                null, null, null);

        messagingTemplate.convertAndSendToUser(request.challengerUsername(), "/queue/match-updates", event);
    }

    // =========================================================================
    // STEP 2c: Cancel Invite (challenger cancels before opponent responds)
    // =========================================================================
    @PostMapping("/cancel")
    public ResponseEntity<String> cancelInvite(@RequestBody CancelRequest request) {
        String inviteId = request.inviteId();
        String challenger = request.challengerUsername();
        String opponent = request.opponentUsername();

        // Validate that the lock exists and matches
        String lockedId = playerLocks.get(challenger);
        if (lockedId == null || !lockedId.equals(inviteId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("No matching invite to cancel.");
        }

        // Release locks
        playerLocks.remove(challenger);
        playerLocks.remove(opponent);

        // Notify opponent that invite was cancelled
        InvitePayload cancelPayload = new InvitePayload(inviteId, challenger, "CANCELLED");
        messagingTemplate.convertAndSendToUser(opponent, "/queue/invites", cancelPayload);

        return ResponseEntity.ok("Invite cancelled.");
    }

    // =========================================================================
    // STEP 3: Report Result (First player submits their claim)
    // =========================================================================
    @PostMapping("/report")
    public ResponseEntity<String> reportResult(@RequestBody ReportRequest request) {
        String matchId = request.matchId();

        // Reject if someone already reported for this match
        if (pendingReports.containsKey(matchId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("A result has already been reported for this match. Waiting for confirmation.");
        }

        // Store the pending report
        pendingReports.put(matchId, new PendingReport(request.reporterUsername(), request.claimedWinner()));

        // Notify BOTH players that confirmation is needed
        Match match = matchRepository.findById(matchId).orElseThrow();
        MatchUpdateEvent event = new MatchUpdateEvent(
                matchId, "AWAITING_CONFIRMATION",
                match.getPlayer1Username(), match.getPlayer2Username(),
                request.reporterUsername(), request.claimedWinner(), null);

        messagingTemplate.convertAndSendToUser(match.getPlayer1Username(), "/queue/match-updates", event);
        messagingTemplate.convertAndSendToUser(match.getPlayer2Username(), "/queue/match-updates", event);

        return ResponseEntity.ok("Report received. Waiting for opponent to confirm.");
    }

    // =========================================================================
    // STEP 4: Confirm Result (Second player responds)
    //         Transitions to REMATCH_OFFERED
    // =========================================================================
    @PostMapping("/confirm")
    public ResponseEntity<String> confirmResult(@RequestBody ConfirmRequest request) {
        String matchId = request.matchId();

        PendingReport pending = pendingReports.get(matchId);
        if (pending == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("No pending report for this match.");
        }

        // Prevent the reporter from confirming their own report
        if (pending.reporterUsername().equals(request.confirmerUsername())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("You already reported. Waiting for opponent.");
        }

        // Compare claims
        boolean agreed = pending.claimedWinner().equals(request.claimedWinner());

        // Update the match in Postgres
        Match match = matchRepository.findById(matchId).orElseThrow();
        if (agreed) {
            match.setWinnerUsername(pending.claimedWinner());
            // Phase 3: Also set the winner UUID
            match.setWinnerId(userService.getUserIdByUsername(pending.claimedWinner()));
            match.setStatus("COMPLETED");
        } else {
            match.setWinnerUsername(null);
            match.setWinnerId(null);
            match.setStatus("DISPUTED");
        }
        matchRepository.save(match);

        // Clean up pending report
        pendingReports.remove(matchId);

        // --- REMATCH FLOW ---
        String result = agreed ? "COMPLETED" : "DISPUTED";
        String winner = agreed ? pending.claimedWinner() : null;

        pendingRematches.put(matchId, new PendingRematch(
                matchId,
                match.getPlayer1Username(),
                match.getPlayer2Username(),
                ConcurrentHashMap.newKeySet()
        ));

        MatchUpdateEvent rematchEvent = new MatchUpdateEvent(
                matchId, "REMATCH_OFFERED",
                match.getPlayer1Username(), match.getPlayer2Username(),
                null, winner, result);

        messagingTemplate.convertAndSendToUser(match.getPlayer1Username(), "/queue/match-updates", rematchEvent);
        messagingTemplate.convertAndSendToUser(match.getPlayer2Username(), "/queue/match-updates", rematchEvent);

        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // STEP 5: Rematch (Both players respond accept/decline)
    // =========================================================================
    @PostMapping("/rematch")
    public ResponseEntity<String> handleRematch(@RequestBody RematchRequest request) {
        String matchId = request.matchId();
        String username = request.username();
        boolean accept = request.accept();

        PendingRematch pending = pendingRematches.get(matchId);
        if (pending == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("No pending rematch for this match.");
        }

        // Validate the player is part of this match
        if (!pending.player1Username().equals(username) && !pending.player2Username().equals(username)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You are not part of this match.");
        }

        // Reject duplicate responses
        if (pending.acceptedPlayers().contains(username)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("You already responded to this rematch.");
        }

        // --- DECLINE ---
        if (!accept) {
            pendingRematches.remove(matchId);
            playerLocks.remove(pending.player1Username());
            playerLocks.remove(pending.player2Username());

            MatchUpdateEvent declinedEvent = new MatchUpdateEvent(
                    matchId, "REMATCH_DECLINED",
                    pending.player1Username(), pending.player2Username(),
                    null, null, null);

            messagingTemplate.convertAndSendToUser(pending.player1Username(), "/queue/match-updates", declinedEvent);
            messagingTemplate.convertAndSendToUser(pending.player2Username(), "/queue/match-updates", declinedEvent);

            return ResponseEntity.ok("Rematch declined.");
        }

        // --- ACCEPT ---
        pending.acceptedPlayers().add(username);

        if (pending.acceptedPlayers().size() == 1) {
            // Only one player accepted so far — notify them they're waiting
            MatchUpdateEvent waitingEvent = new MatchUpdateEvent(
                    matchId, "REMATCH_WAITING",
                    pending.player1Username(), pending.player2Username(),
                    null, null, null);

            messagingTemplate.convertAndSendToUser(username, "/queue/match-updates", waitingEvent);

            return ResponseEntity.ok("Waiting for opponent.");
        }

        // Both players accepted — start a new match!
        pendingRematches.remove(matchId);

        // Phase 3: Resolve UUIDs for the rematch
        UUID player1Id = userService.getUserIdByUsername(pending.player1Username());
        UUID player2Id = userService.getUserIdByUsername(pending.player2Username());

        Match newMatch = new Match(
                pending.player1Username(), pending.player2Username(),
                player1Id, player2Id);
        matchRepository.save(newMatch);

        // Send STARTED to both — locks remain held
        MatchUpdateEvent startedEvent = new MatchUpdateEvent(
                newMatch.getId(), "STARTED",
                newMatch.getPlayer1Username(), newMatch.getPlayer2Username(),
                null, null, null);

        messagingTemplate.convertAndSendToUser(pending.player1Username(), "/queue/match-updates", startedEvent);
        messagingTemplate.convertAndSendToUser(pending.player2Username(), "/queue/match-updates", startedEvent);

        return ResponseEntity.ok("Rematch started! New match ID: " + newMatch.getId());
    }

    // =========================================================================
    // DTOs
    // =========================================================================
    public record InviteRequest(String challengerUsername, String targetUsername) {}
    public record InvitePayload(String inviteId, String from, String status) {}
    public record AcceptRequest(String inviteId, String challengerUsername, String opponentUsername) {}
    public record DeclineRequest(String inviteId, String challengerUsername, String opponentUsername) {}
    public record CancelRequest(String inviteId, String challengerUsername, String opponentUsername) {}

    // Report: first player submits their claim
    public record ReportRequest(String matchId, String reporterUsername, String claimedWinner) {}

    // Confirm: second player submits their independent view
    public record ConfirmRequest(String matchId, String confirmerUsername, String claimedWinner) {}

    // Rematch: player accepts or declines rematch offer
    public record RematchRequest(String matchId, String username, boolean accept) {}

    // Internal: held in pendingReports map
    public record PendingReport(String reporterUsername, String claimedWinner) {}

    // Internal: held in pendingRematches map
    public record PendingRematch(String matchId, String player1Username, String player2Username,
                                 Set<String> acceptedPlayers) {}

    // WebSocket event — sent on /user/queue/match-updates
    // reporterUsername & claimedWinner populated for AWAITING_CONFIRMATION
    // result populated for REMATCH_OFFERED ("COMPLETED" or "DISPUTED")
    // claimedWinner populated for REMATCH_OFFERED when result is "COMPLETED" (the winner)
    public record MatchUpdateEvent(String matchId, String status,
                                   String player1, String player2,
                                   String reporterUsername, String claimedWinner,
                                   String result) {}
}