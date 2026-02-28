package com.smashrank.smashrank_api.controller;

import com.smashrank.smashrank_api.model.Match;
import com.smashrank.smashrank_api.repository.MatchRepository;
import com.smashrank.smashrank_api.service.EloService;
import com.smashrank.smashrank_api.service.PoolService;
import com.smashrank.smashrank_api.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private static final Logger log = LoggerFactory.getLogger(MatchController.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final MatchRepository matchRepository;
    private final UserService userService;

    // Injected timeout durations (overridden to 1s in application-test.properties)
    @Value("${smashrank.match.confirm-timeout-seconds:20}")
    private int confirmTimeoutSeconds;

    @Value("${smashrank.match.rematch-timeout-seconds:20}")
    private int rematchTimeoutSeconds;

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

    private final EloService eloService;

    private final PoolService poolService;  // To look up characters from Redis

    public MatchController(MatchRepository matchRepository,
                           SimpMessagingTemplate messagingTemplate,
                           UserService userService,
                           EloService eloService,
                           PoolService poolService) {
        this.matchRepository = matchRepository;
        this.messagingTemplate = messagingTemplate;
        this.userService = userService;
        this.eloService = eloService;
        this.poolService = poolService;
    }

    // =========================================================================
    // STEP 1: Send Invite
    // =========================================================================
    @PostMapping("/invite")
    public ResponseEntity<String> sendInvite(@RequestBody InviteRequest request) {
        String challenger = request.challengerUsername();
        String opponent = request.targetUsername();
        log.info("Invite request from {} to {}", challenger, opponent);

        // 1. Race Condition Check
        if (playerLocks.containsKey(challenger)) {
            log.warn("Invite failed: Challenger {} already has a pending invite.", challenger);
            return ResponseEntity.status(HttpStatus.CONFLICT).body("You already have a pending invite.");
        }
        if (playerLocks.containsKey(opponent)) {
            log.warn("Invite failed: Opponent {} is busy.", opponent);
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Player is busy (likely sending you an invite!)");
        }

        // 2. Lock players
        String inviteId = UUID.randomUUID().toString();
        playerLocks.put(challenger, inviteId);
        playerLocks.put(opponent, inviteId);
        log.debug("Players {} and {} locked with inviteId {}", challenger, opponent, inviteId);

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
        log.info("Accepting invite {} for players {} and {}", inviteId, request.challengerUsername(), request.opponentUsername());

        // Security: Ensure the lock matches the invite
        String lockedId = playerLocks.get(request.challengerUsername());
        if (lockedId == null || !lockedId.equals(inviteId)) {
            log.error("Accept failed: Invite {} expired or invalid for challenger {}", inviteId, request.challengerUsername());
            throw new IllegalStateException("Invite expired or invalid");
        }

        // Look up characters from the pool
        String challengerChar = poolService.getCheckedInCharacter(request.challengerUsername());
        String opponentChar = poolService.getCheckedInCharacter(request.opponentUsername());

        // Create the official match record (with both username and UUID fields)
        // Create match WITH character data
        Match match = new Match(
                request.challengerUsername(),
                request.opponentUsername(),
                userService.getUserIdByUsername(request.challengerUsername()),
                userService.getUserIdByUsername(request.opponentUsername()),
                challengerChar != null ? challengerChar : "Unknown",
                opponentChar != null ? opponentChar : "Unknown"
        );
        matchRepository.save(match);
        log.info("Match created with ID: {}", match.getId());

        MatchUpdateEvent event = new MatchUpdateEvent(
                match.getId(), "STARTED",
                request.challengerUsername(), request.opponentUsername(),
                null, null, null,
                null, null, null, null,            // Elo fields (null for STARTED)
                challengerChar, opponentChar       // Character fields
        );

        messagingTemplate.convertAndSendToUser(request.challengerUsername(), "/queue/match-updates", event);
        messagingTemplate.convertAndSendToUser(request.opponentUsername(), "/queue/match-updates", event);
    }

    // =========================================================================
    // STEP 2b: Decline Invite
    // =========================================================================
    @PostMapping("/decline")
    public void declineInvite(@RequestBody DeclineRequest request) {
        log.info("Declining invite for players {} and {}", request.challengerUsername(), request.opponentUsername());
        // Release locks
        playerLocks.remove(request.challengerUsername());
        playerLocks.remove(request.opponentUsername());

        // Notify challenger
        MatchUpdateEvent event = new MatchUpdateEvent(
                null, "DECLINED",
                request.challengerUsername(), request.opponentUsername(),
                null, null, null,
                null, null, null, null,           // Elo
                null, null                        // Characters (no match)
        );

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
        log.info("Cancelling invite {} from {}", inviteId, challenger);

        // Validate that the lock exists and matches
        String lockedId = playerLocks.get(challenger);
        if (lockedId == null || !lockedId.equals(inviteId)) {
            log.warn("Cancel failed: No matching invite {} for challenger {}", inviteId, challenger);
            return ResponseEntity.status(HttpStatus.CONFLICT).body("No matching invite to cancel.");
        }

        // Release locks
        playerLocks.remove(challenger);
        playerLocks.remove(opponent);
        log.debug("Locks released for {} and {}", challenger, opponent);

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
        log.info("Result reported for match {}: reporter={}, winner={}", matchId, request.reporterUsername(), request.claimedWinner());

        // Reject if someone already reported for this match
        if (pendingReports.containsKey(matchId)) {
            log.warn("Report failed: Result already reported for match {}", matchId);
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
                request.reporterUsername(), request.claimedWinner(), null,
                null, null, null, null,
                match.getPlayer1Character(), match.getPlayer2Character()
        );

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
        log.info("Confirming result for match {}: confirmer={}, winner={}", matchId, request.confirmerUsername(), request.claimedWinner());

        PendingReport pending = pendingReports.get(matchId);
        if (pending == null) {
            log.warn("Confirm failed: No pending report for match {}", matchId);
            return ResponseEntity.status(HttpStatus.CONFLICT).body("No pending report for this match.");
        }

        // Prevent the reporter from confirming their own report
        if (pending.reporterUsername().equals(request.confirmerUsername())) {
            log.warn("Confirm failed: Reporter {} tried to confirm their own report for match {}", request.confirmerUsername(), matchId);
            return ResponseEntity.status(HttpStatus.CONFLICT).body("You already reported. Waiting for opponent.");
        }

        // Compare claims
        boolean agreed = pending.claimedWinner().equals(request.claimedWinner());

        Match match = matchRepository.findById(matchId).orElseThrow();

        EloService.EloResult eloResult = null;

        if (agreed) {
            match.setWinnerUsername(pending.claimedWinner());
            match.setWinnerId(userService.getUserIdByUsername(pending.claimedWinner()));
            match.setStatus("COMPLETED");

            // Process per-character Elo update
            eloResult = eloService.processMatchResult(match);
        } else {
            match.setWinnerUsername(null);
            match.setWinnerId(null);
            match.setStatus("DISPUTED");
        }
        matchRepository.save(match);

        pendingReports.remove(matchId);

        // REMATCH FLOW
        String result = agreed ? "COMPLETED" : "DISPUTED";
        String winner = agreed ? pending.claimedWinner() : null;

        Integer p1EloDelta = null, p2EloDelta = null;
        Integer p1NewElo = null, p2NewElo = null;
        if (eloResult != null) {
            p1EloDelta = eloResult.player1Delta();
            p2EloDelta = eloResult.player2Delta();
            p1NewElo = eloResult.player1EloAfter();
            p2NewElo = eloResult.player2EloAfter();
        }

        // Store rematch tracking...
        pendingRematches.put(matchId, new PendingRematch(
                matchId,
                match.getPlayer1Username(),
                match.getPlayer2Username(),
                ConcurrentHashMap.newKeySet()
        ));

        MatchUpdateEvent rematchEvent = new MatchUpdateEvent(
                matchId, "REMATCH_OFFERED",
                match.getPlayer1Username(), match.getPlayer2Username(),
                null, winner, result,
                p1EloDelta, p2EloDelta, p1NewElo, p2NewElo,
                match.getPlayer1Character(), match.getPlayer2Character()
        );

        messagingTemplate.convertAndSendToUser(
                match.getPlayer1Username(), "/queue/match-updates", rematchEvent);
        messagingTemplate.convertAndSendToUser(
                match.getPlayer2Username(), "/queue/match-updates", rematchEvent);

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
        log.info("Rematch response from {} for match {}: accept={}", username, matchId, accept);

        PendingRematch pending = pendingRematches.get(matchId);
        if (pending == null) {
            log.warn("Rematch failed: No pending rematch for match {}", matchId);
            return ResponseEntity.status(HttpStatus.CONFLICT).body("No pending rematch for this match.");
        }

        // Validate the player is part of this match
        if (!pending.player1Username().equals(username) && !pending.player2Username().equals(username)) {
            log.error("Rematch failed: User {} is not part of match {}", username, matchId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You are not part of this match.");
        }

        // Reject duplicate responses
        if (pending.acceptedPlayers().contains(username)) {
            log.warn("Rematch failed: User {} already responded for match {}", username, matchId);
            return ResponseEntity.status(HttpStatus.CONFLICT).body("You already responded to this rematch.");
        }

        // --- DECLINE ---
        if (!accept) {
            log.info("Rematch declined by {} for match {}", username, matchId);
            pendingRematches.remove(matchId);
            playerLocks.remove(pending.player1Username());
            playerLocks.remove(pending.player2Username());

            MatchUpdateEvent declinedEvent = new MatchUpdateEvent(
                    matchId, "REMATCH_DECLINED",
                    pending.player1Username(), pending.player2Username(),
                    null, null, null,
                    null, null, null, null,
                    null, null
            );

            messagingTemplate.convertAndSendToUser(pending.player1Username(), "/queue/match-updates", declinedEvent);
            messagingTemplate.convertAndSendToUser(pending.player2Username(), "/queue/match-updates", declinedEvent);

            return ResponseEntity.ok("Rematch declined.");
        }

        // --- ACCEPT ---
        pending.acceptedPlayers().add(username);
        log.debug("User {} accepted rematch for match {}", username, matchId);

        if (pending.acceptedPlayers().size() == 1) {
            // Only one player accepted so far — notify them they're waiting
            Match match = matchRepository.findById(matchId).orElseThrow();
            MatchUpdateEvent waitingEvent = new MatchUpdateEvent(
                    matchId, "REMATCH_WAITING",
                    pending.player1Username(), pending.player2Username(),
                    null, null, null,
                    null, null, null, null,
                    match.getPlayer1Character(), match.getPlayer2Character()
            );

            messagingTemplate.convertAndSendToUser(username, "/queue/match-updates", waitingEvent);

            return ResponseEntity.ok("Waiting for opponent.");
        }

        // Both players accepted — start a new match!
        log.info("Rematch accepted by both players for match {}. Creating new match.", matchId);
        pendingRematches.remove(matchId);

        // Phase 3: Resolve UUIDs for the rematch
        UUID player1Id = userService.getUserIdByUsername(pending.player1Username());
        UUID player2Id = userService.getUserIdByUsername(pending.player2Username());

        Match oldMatch = matchRepository.findById(matchId).orElseThrow();
        Match newMatch = new Match(
                pending.player1Username(),
                pending.player2Username(),
                player1Id,
                player2Id,
                // Characters carry over (no switching in Quickplay rematches)
                oldMatch.getPlayer1Character(),
                oldMatch.getPlayer2Character()
        );
        matchRepository.save(newMatch);

        // STARTED event for the rematch includes characters
        MatchUpdateEvent startEvent = new MatchUpdateEvent(
                newMatch.getId(), "STARTED",
                pending.player1Username(), pending.player2Username(),
                null, null, null,
                null, null, null, null,
                newMatch.getPlayer1Character(), newMatch.getPlayer2Character()
        );

        messagingTemplate.convertAndSendToUser(pending.player1Username(), "/queue/match-updates", startEvent);
        messagingTemplate.convertAndSendToUser(pending.player2Username(), "/queue/match-updates", startEvent);

        return ResponseEntity.ok("Rematch started! New match ID: " + newMatch.getId());
    }

    // =========================================================================
    // DTOs
    // =========================================================================
    public record InviteRequest(String challengerUsername, String targetUsername) {
    }

    public record InvitePayload(String inviteId, String from, String status) {
    }

    public record AcceptRequest(String inviteId, String challengerUsername, String opponentUsername) {
    }

    public record DeclineRequest(String inviteId, String challengerUsername, String opponentUsername) {
    }

    public record CancelRequest(String inviteId, String challengerUsername, String opponentUsername) {
    }

    // Report: first player submits their claim
    public record ReportRequest(String matchId, String reporterUsername, String claimedWinner) {
    }

    // Confirm: second player submits their independent view
    public record ConfirmRequest(String matchId, String confirmerUsername, String claimedWinner) {
    }

    // Rematch: player accepts or declines rematch offer
    public record RematchRequest(String matchId, String username, boolean accept) {
    }

    // Internal: held in pendingReports map
    public record PendingReport(String reporterUsername, String claimedWinner) {
    }

    // Internal: held in pendingRematches map
    public record PendingRematch(String matchId, String player1Username, String player2Username,
                                 Set<String> acceptedPlayers) {
    }

    /**
     * WebSocket event — sent on /user/queue/match-updates
     *
     * All states include player1Character/player2Character.
     * Elo fields only populated on REMATCH_OFFERED with result=COMPLETED.
     */
    public record MatchUpdateEvent(
            String matchId,
            String status,
            String player1,
            String player2,
            String reporterUsername,
            String claimedWinner,
            String result,
            // Elo fields (null unless COMPLETED)
            Integer player1EloDelta,
            Integer player2EloDelta,
            Integer player1NewElo,
            Integer player2NewElo,
            // Character fields
            String player1Character,
            String player2Character
    ) {}
}