package com.smashrank.smashrank_api;

import com.smashrank.smashrank_api.controller.MatchController;
import com.smashrank.smashrank_api.controller.MatchController.*;
import com.smashrank.smashrank_api.model.Match;
import com.smashrank.smashrank_api.repository.MatchRepository;
import com.smashrank.smashrank_api.service.EloService;
import com.smashrank.smashrank_api.service.PoolService;
import com.smashrank.smashrank_api.service.UserService;
import com.smashrank.util.TestFixtures;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 1 — Unit Tests (MatchController Logic)
 *
 * Verifies every branch of match state logic in isolation:
 * no HTTP, no WebSocket, no DB.
 *
 * Run: ./mvnw test -Dtest="*UnitTest"
 */
@ExtendWith(MockitoExtension.class)
class MatchControllerUnitTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private MatchRepository matchRepository;
    @Mock private UserService userService;
    @Mock private EloService eloService;
    @Mock private PoolService poolService;

    private MatchController controller;

    // Direct access to internal state maps
    @SuppressWarnings("unchecked")
    private Map<String, String> playerLocks;
    @SuppressWarnings("unchecked")
    private Map<String, PendingReport> pendingReports;
    @SuppressWarnings("unchecked")
    private Map<String, PendingRematch> pendingRematches;

    @BeforeEach
    void setUp() {
        controller = new MatchController(
                matchRepository, messagingTemplate, userService, eloService, poolService);

        // Access private maps via reflection for direct state manipulation
        playerLocks = (Map<String, String>)
                ReflectionTestUtils.getField(controller, "playerLocks");
        pendingReports = (Map<String, PendingReport>)
                ReflectionTestUtils.getField(controller, "pendingReports");
        pendingRematches = (Map<String, PendingRematch>)
                ReflectionTestUtils.getField(controller, "pendingRematches");
    }

    // =========================================================================
    // 1.1 — Lock Semantics
    // =========================================================================

    @Nested
    @DisplayName("1.1 — Lock Semantics")
    class LockSemantics {

        @Test
        @DisplayName("canInvite_whenBothPlayersIdle_acquiresLocks")
        void canInvite_whenBothPlayersIdle_acquiresLocks() {
            ResponseEntity<String> response = controller.sendInvite(
                    new InviteRequest("playerA", "playerB"));

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(playerLocks.containsKey("playerA"));
            assertTrue(playerLocks.containsKey("playerB"));
            // Both players should be locked with the same inviteId
            assertEquals(playerLocks.get("playerA"), playerLocks.get("playerB"));
            // The response body is the inviteId
            assertEquals(playerLocks.get("playerA"), response.getBody());
        }

        @Test
        @DisplayName("cannotInvite_whenChallengerAlreadyLocked_returns409")
        void cannotInvite_whenChallengerAlreadyLocked_returns409() {
            playerLocks.put("playerA", "existing-invite-id");

            ResponseEntity<String> response = controller.sendInvite(
                    new InviteRequest("playerA", "playerB"));

            assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
            // Opponent should NOT have been locked
            assertFalse(playerLocks.containsKey("playerB"));
            // Challenger's existing lock should be untouched
            assertEquals("existing-invite-id", playerLocks.get("playerA"));
        }

        @Test
        @DisplayName("cannotInvite_whenTargetAlreadyLocked_returns409")
        void cannotInvite_whenTargetAlreadyLocked_returns409() {
            playerLocks.put("playerB", "existing-invite-id");

            ResponseEntity<String> response = controller.sendInvite(
                    new InviteRequest("playerA", "playerB"));

            assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
            // Challenger should NOT have been locked
            assertFalse(playerLocks.containsKey("playerA"));
            // Target's existing lock should be untouched
            assertEquals("existing-invite-id", playerLocks.get("playerB"));
        }

        @Test
        @DisplayName("cancel_releasesLockForBothPlayers")
        void cancel_releasesLockForBothPlayers() {
            String inviteId = "test-invite-id";
            playerLocks.put("playerA", inviteId);
            playerLocks.put("playerB", inviteId);

            ResponseEntity<String> response = controller.cancelInvite(
                    new CancelRequest(inviteId, "playerA", "playerB"));

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertFalse(playerLocks.containsKey("playerA"));
            assertFalse(playerLocks.containsKey("playerB"));

            // Verify opponent was notified of cancellation
            verify(messagingTemplate).convertAndSendToUser(
                    eq("playerB"), eq("/queue/invites"), any(InvitePayload.class));
        }

        @Test
        @DisplayName("decline_releasesLockForBothPlayers")
        void decline_releasesLockForBothPlayers() {
            String inviteId = "test-invite-id";
            playerLocks.put("playerA", inviteId);
            playerLocks.put("playerB", inviteId);

            controller.declineInvite(
                    new DeclineRequest(inviteId, "playerA", "playerB"));

            assertFalse(playerLocks.containsKey("playerA"));
            assertFalse(playerLocks.containsKey("playerB"));

            // Verify challenger was notified of decline
            verify(messagingTemplate).convertAndSendToUser(
                    eq("playerA"), eq("/queue/match-updates"), any(MatchUpdateEvent.class));
        }
    }

    // =========================================================================
    // 1.2 — Report Idempotency
    // =========================================================================

    @Nested
    @DisplayName("1.2 — Report Idempotency")
    class ReportIdempotency {

        @Test
        @DisplayName("report_firstCall_createsEntryInPendingReports")
        void report_firstCall_createsEntryInPendingReports() {
            Match match = TestFixtures.buildMatch("playerA", "playerB");
            when(matchRepository.findById(match.getId())).thenReturn(Optional.of(match));

            ResponseEntity<String> response = controller.reportResult(
                    new ReportRequest(match.getId(), "playerA", "playerA"));

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(pendingReports.containsKey(match.getId()));

            PendingReport report = pendingReports.get(match.getId());
            assertEquals("playerA", report.reporterUsername());
            assertEquals("playerA", report.claimedWinner());

            // Verify both players were notified of AWAITING_CONFIRMATION
            verify(messagingTemplate).convertAndSendToUser(
                    eq("playerA"), eq("/queue/match-updates"), any(MatchUpdateEvent.class));
            verify(messagingTemplate).convertAndSendToUser(
                    eq("playerB"), eq("/queue/match-updates"), any(MatchUpdateEvent.class));
        }

        @Test
        @DisplayName("report_secondCallSameMatch_isIgnoredAndDoesNotOverwrite (regression: putIfAbsent)")
        void report_secondCallSameMatch_isIgnoredAndDoesNotOverwrite() {
            Match match = TestFixtures.buildMatch("playerA", "playerB");
            when(matchRepository.findById(match.getId())).thenReturn(Optional.of(match));

            // First report succeeds
            controller.reportResult(
                    new ReportRequest(match.getId(), "playerA", "playerA"));

            // Second report with different reporter and different claim
            ResponseEntity<String> response = controller.reportResult(
                    new ReportRequest(match.getId(), "playerB", "playerB"));

            assertEquals(HttpStatus.CONFLICT, response.getStatusCode());

            // Original report must NOT be overwritten
            PendingReport report = pendingReports.get(match.getId());
            assertEquals("playerA", report.reporterUsername());
            assertEquals("playerA", report.claimedWinner());
        }

        @Test
        @DisplayName("report_differentMatches_createsSeparateEntries")
        void report_differentMatches_createsSeparateEntries() {
            Match matchA = TestFixtures.buildMatch("playerA", "playerB");
            Match matchB = TestFixtures.buildMatch("playerC", "playerD");
            when(matchRepository.findById(matchA.getId())).thenReturn(Optional.of(matchA));
            when(matchRepository.findById(matchB.getId())).thenReturn(Optional.of(matchB));

            controller.reportResult(
                    new ReportRequest(matchA.getId(), "playerA", "playerA"));
            controller.reportResult(
                    new ReportRequest(matchB.getId(), "playerC", "playerC"));

            assertEquals(2, pendingReports.size());
            assertEquals("playerA", pendingReports.get(matchA.getId()).reporterUsername());
            assertEquals("playerC", pendingReports.get(matchB.getId()).reporterUsername());
        }
    }

    // =========================================================================
    // 1.3 — Confirm Logic (All Four Outcome Combinations)
    // =========================================================================

    @Nested
    @DisplayName("1.3 — Confirm Logic")
    class ConfirmLogic {

        /**
         * Helper: pre-populate a pending report and mock the match repository.
         */
        private Match setupConfirmScenario(String matchId,
                                           String reporter,
                                           String reportedWinner) {
            Match match = TestFixtures.buildMatch("playerA", "playerB");
            ReflectionTestUtils.setField(match, "id", matchId);
            pendingReports.put(matchId, new PendingReport(reporter, reportedWinner));
            when(matchRepository.findById(matchId)).thenReturn(Optional.of(match));
            return match;
        }

        @Test
        @DisplayName("confirm_bothSayPlayerAWon_completesMatchWithPlayerAAsWinner")
        void confirm_bothSayPlayerAWon_completesMatchWithPlayerAAsWinner() {
            String matchId = "match-agree-a-wins";
            Match match = setupConfirmScenario(matchId, "playerA", "playerA");

            UUID winnerUuid = UUID.randomUUID();
            when(userService.getUserIdByUsername("playerA")).thenReturn(winnerUuid);
            when(eloService.processMatchResult(match)).thenReturn(
                    new EloService.EloResult("playerA", "playerB", "Fox", "Marth",
                            1500, 1520, 20, 1500, 1480, -20));

            // playerB confirms playerA won (agrees with reporter)
            ResponseEntity<String> response = controller.confirmResult(
                    new ConfirmRequest(matchId, "playerB", "playerA"));

            assertEquals("COMPLETED", response.getBody());
            assertEquals("COMPLETED", match.getStatus());
            assertEquals("playerA", match.getWinnerUsername());
            assertEquals(winnerUuid, match.getWinnerId());
            verify(eloService).processMatchResult(match);
            verify(matchRepository).save(match);

            // Pending report should be consumed
            assertFalse(pendingReports.containsKey(matchId));
            // Rematch should be offered
            assertTrue(pendingRematches.containsKey(matchId));

            // Verify STOMP event sent to both players
            ArgumentCaptor<MatchUpdateEvent> captor =
                    ArgumentCaptor.forClass(MatchUpdateEvent.class);
            verify(messagingTemplate, times(2)).convertAndSendToUser(
                    anyString(), eq("/queue/match-updates"), captor.capture());
            MatchUpdateEvent event = captor.getValue();
            assertEquals("REMATCH_OFFERED", event.status());
            assertEquals("COMPLETED", event.result());
            assertEquals("playerA", event.claimedWinner());
            assertEquals(20, event.player1EloDelta());
            assertEquals(-20, event.player2EloDelta());
        }

        @Test
        @DisplayName("confirm_bothSayPlayerBWon_completesMatchWithPlayerBAsWinner")
        void confirm_bothSayPlayerBWon_completesMatchWithPlayerBAsWinner() {
            String matchId = "match-agree-b-wins";
            Match match = setupConfirmScenario(matchId, "playerA", "playerB");

            UUID winnerUuid = UUID.randomUUID();
            when(userService.getUserIdByUsername("playerB")).thenReturn(winnerUuid);
            when(eloService.processMatchResult(match)).thenReturn(
                    new EloService.EloResult("playerA", "playerB", "Fox", "Marth",
                            1500, 1480, -20, 1500, 1520, 20));

            // playerB confirms playerB won (agrees with reporter)
            ResponseEntity<String> response = controller.confirmResult(
                    new ConfirmRequest(matchId, "playerB", "playerB"));

            assertEquals("COMPLETED", response.getBody());
            assertEquals("COMPLETED", match.getStatus());
            assertEquals("playerB", match.getWinnerUsername());
            assertEquals(winnerUuid, match.getWinnerId());
            verify(eloService).processMatchResult(match);

            // Verify STOMP event
            ArgumentCaptor<MatchUpdateEvent> captor =
                    ArgumentCaptor.forClass(MatchUpdateEvent.class);
            verify(messagingTemplate, times(2)).convertAndSendToUser(
                    anyString(), eq("/queue/match-updates"), captor.capture());
            assertEquals("COMPLETED", captor.getValue().result());
            assertEquals("playerB", captor.getValue().claimedWinner());
        }

        @Test
        @DisplayName("confirm_playerASaysAWon_playerBSaysBWon_createsDisputedMatch")
        void confirm_playerASaysAWon_playerBSaysBWon_createsDisputedMatch() {
            String matchId = "match-dispute-1";
            Match match = setupConfirmScenario(matchId, "playerA", "playerA");

            // playerB says playerB won (disagrees)
            ResponseEntity<String> response = controller.confirmResult(
                    new ConfirmRequest(matchId, "playerB", "playerB"));

            assertEquals("DISPUTED", response.getBody());
            assertEquals("DISPUTED", match.getStatus());
            assertNull(match.getWinnerUsername());
            assertNull(match.getWinnerId());
            verify(eloService, never()).processMatchResult(any());
            verify(matchRepository).save(match);

            // Pending report consumed, rematch still offered
            assertFalse(pendingReports.containsKey(matchId));
            assertTrue(pendingRematches.containsKey(matchId));

            // Verify STOMP event shows DISPUTED
            ArgumentCaptor<MatchUpdateEvent> captor =
                    ArgumentCaptor.forClass(MatchUpdateEvent.class);
            verify(messagingTemplate, times(2)).convertAndSendToUser(
                    anyString(), eq("/queue/match-updates"), captor.capture());
            assertEquals("DISPUTED", captor.getValue().result());
            assertNull(captor.getValue().claimedWinner());
            assertNull(captor.getValue().player1EloDelta());
            assertNull(captor.getValue().player2EloDelta());
        }

        @Test
        @DisplayName("confirm_playerASaysBWon_playerBSaysAWon_createsDisputedMatch")
        void confirm_playerASaysBWon_playerBSaysAWon_createsDisputedMatch() {
            String matchId = "match-dispute-2";
            Match match = setupConfirmScenario(matchId, "playerA", "playerB");

            // playerB says playerA won (disagrees with reporter who said playerB)
            ResponseEntity<String> response = controller.confirmResult(
                    new ConfirmRequest(matchId, "playerB", "playerA"));

            assertEquals("DISPUTED", response.getBody());
            assertEquals("DISPUTED", match.getStatus());
            assertNull(match.getWinnerUsername());
            assertNull(match.getWinnerId());
            verify(eloService, never()).processMatchResult(any());
        }
    }

    // =========================================================================
    // 1.4 — Rematch State Machine
    // =========================================================================

    @Nested
    @DisplayName("1.4 — Rematch State Machine")
    class RematchStateMachine {

        /**
         * Helper: set up a pending rematch entry with both players unlocked.
         */
        private void setupPendingRematch(String matchId) {
            Set<String> accepted = ConcurrentHashMap.newKeySet();
            pendingRematches.put(matchId, new PendingRematch(
                    matchId, "playerA", "playerB", accepted));
        }

        @Test
        @DisplayName("rematch_bothAccept_createsNewMatchAndSendsStarted")
        void rematch_bothAccept_createsNewMatchAndSendsStarted() {
            String matchId = "match-rematch-accept";
            setupPendingRematch(matchId);

            // Mock old match lookup (called by both accepts)
            Match oldMatch = TestFixtures.buildMatch("playerA", "playerB");
            ReflectionTestUtils.setField(oldMatch, "id", matchId);
            when(matchRepository.findById(matchId)).thenReturn(Optional.of(oldMatch));

            // Mock user ID lookups (called when both accept)
            when(userService.getUserIdByUsername("playerA")).thenReturn(UUID.randomUUID());
            when(userService.getUserIdByUsername("playerB")).thenReturn(UUID.randomUUID());

            // Mock save to set ID on new match
            when(matchRepository.save(any(Match.class))).thenAnswer(invocation -> {
                Match m = invocation.getArgument(0);
                ReflectionTestUtils.setField(m, "id", UUID.randomUUID().toString());
                return m;
            });

            // First player accepts → WAITING
            ResponseEntity<String> firstResponse = controller.handleRematch(
                    new RematchRequest(matchId, "playerA", true));
            assertEquals("Waiting for opponent.", firstResponse.getBody());

            // Second player accepts → NEW MATCH
            ResponseEntity<String> secondResponse = controller.handleRematch(
                    new RematchRequest(matchId, "playerB", true));
            assertTrue(secondResponse.getBody().startsWith("Rematch started!"));

            // Pending rematch should be cleaned up
            assertFalse(pendingRematches.containsKey(matchId));

            // New match should have been saved
            verify(matchRepository).save(any(Match.class));

            // Verify STARTED events sent to both players
            ArgumentCaptor<MatchUpdateEvent> captor =
                    ArgumentCaptor.forClass(MatchUpdateEvent.class);
            verify(messagingTemplate, atLeast(2)).convertAndSendToUser(
                    anyString(), eq("/queue/match-updates"), captor.capture());

            // The last two events should be STARTED for the new match
            List<MatchUpdateEvent> events = captor.getAllValues();
            MatchUpdateEvent lastEvent = events.get(events.size() - 1);
            assertEquals("STARTED", lastEvent.status());
        }

        @Test
        @DisplayName("rematch_firstPlayerAccepts_secondDeclines_releasesLocksAndSendsDeclined")
        void rematch_firstPlayerAccepts_secondDeclines_releasesLocksAndSendsDeclined() {
            String matchId = "match-rematch-decline-second";
            setupPendingRematch(matchId);
            playerLocks.put("playerA", "some-invite");
            playerLocks.put("playerB", "some-invite");

            // Old match for the WAITING event
            Match oldMatch = TestFixtures.buildMatch("playerA", "playerB");
            ReflectionTestUtils.setField(oldMatch, "id", matchId);
            when(matchRepository.findById(matchId)).thenReturn(Optional.of(oldMatch));

            // First player accepts
            controller.handleRematch(new RematchRequest(matchId, "playerA", true));

            // Second player declines
            ResponseEntity<String> response = controller.handleRematch(
                    new RematchRequest(matchId, "playerB", false));

            assertEquals("Rematch declined.", response.getBody());
            assertFalse(pendingRematches.containsKey(matchId));
            assertFalse(playerLocks.containsKey("playerA"));
            assertFalse(playerLocks.containsKey("playerB"));

            // Verify REMATCH_DECLINED event sent to both
            ArgumentCaptor<MatchUpdateEvent> captor =
                    ArgumentCaptor.forClass(MatchUpdateEvent.class);
            verify(messagingTemplate, atLeast(2)).convertAndSendToUser(
                    anyString(), eq("/queue/match-updates"), captor.capture());
            boolean hasDeclined = captor.getAllValues().stream()
                    .anyMatch(e -> "REMATCH_DECLINED".equals(e.status()));
            assertTrue(hasDeclined, "Should send REMATCH_DECLINED event");
        }

        @Test
        @DisplayName("rematch_firstPlayerDeclines_immediately_releasesLocksWithoutWaitingForSecond")
        void rematch_firstPlayerDeclines_immediately_releasesLocksWithoutWaitingForSecond() {
            String matchId = "match-rematch-decline-first";
            setupPendingRematch(matchId);
            playerLocks.put("playerA", "some-invite");
            playerLocks.put("playerB", "some-invite");

            // First player declines immediately (no waiting for second)
            ResponseEntity<String> response = controller.handleRematch(
                    new RematchRequest(matchId, "playerA", false));

            assertEquals("Rematch declined.", response.getBody());
            assertFalse(pendingRematches.containsKey(matchId));
            // BOTH locks should be released immediately
            assertFalse(playerLocks.containsKey("playerA"));
            assertFalse(playerLocks.containsKey("playerB"));
        }

        @Test
        @DisplayName("rematch_accept_afterTimeoutHasAlreadyCleanedUp_isIgnoredGracefully")
        void rematch_accept_afterTimeoutHasAlreadyCleanedUp_isIgnoredGracefully() {
            // No pending rematch — simulates timeout having already cleaned up
            String matchId = "match-rematch-expired";

            ResponseEntity<String> response = controller.handleRematch(
                    new RematchRequest(matchId, "playerA", true));

            assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
            // No exception, no side effects
            assertTrue(pendingRematches.isEmpty());
        }
    }

    // =========================================================================
    // 1.5 — Elo Calculation
    // =========================================================================

    @Nested
    @DisplayName("1.5 — Elo Calculation")
    class EloCalculation {

        // Reference to the production EloCalculator for direct math tests
        // (it's a pure utility — no mocking needed)

        @Test
        @DisplayName("eloUpdate_winnerEloIncreases")
        void eloUpdate_winnerEloIncreases() {
            int delta = com.smashrank.smashrank_api.service.EloCalculator
                    .calculateDelta(1500, 1500, true, 0);
            assertTrue(delta > 0, "Winner's Elo delta should be positive");
        }

        @Test
        @DisplayName("eloUpdate_loserEloDecreases")
        void eloUpdate_loserEloDecreases() {
            int delta = com.smashrank.smashrank_api.service.EloCalculator
                    .calculateDelta(1500, 1500, false, 0);
            assertTrue(delta < 0, "Loser's Elo delta should be negative");
        }

        @Test
        @DisplayName("eloUpdate_isMathematicallyCorrect_givenKnownInputs")
        void eloUpdate_isMathematicallyCorrect_givenKnownInputs() {
            // Cross-check production EloCalculator against test-scoped mirror
            int prodDelta = com.smashrank.smashrank_api.service.EloCalculator
                    .calculateDelta(1500, 1500, true, 0);
            int testDelta = com.smashrank.util.EloCalculator
                    .delta(1500, 1500, true, 0);
            assertEquals(testDelta, prodDelta,
                    "Production and test EloCalculator must agree");

            // Known calculation: equal Elo, K=40 (0 games), win
            // expected = 0.5, delta = round(40 * (1.0 - 0.5)) = 20
            assertEquals(20, prodDelta);

            // Verify with asymmetric Elo
            int upsetDelta = com.smashrank.smashrank_api.service.EloCalculator
                    .calculateDelta(1200, 1800, true, 0);
            int upsetTestDelta = com.smashrank.util.EloCalculator
                    .delta(1200, 1800, true, 0);
            assertEquals(upsetTestDelta, upsetDelta,
                    "Production and test EloCalculator must agree for asymmetric ratings");
        }

        @Test
        @DisplayName("eloUpdate_isZeroSum_totalEloConserved")
        void eloUpdate_isZeroSum_totalEloConserved() {
            // Equal ratings — zero-sum is exact
            int winnerDelta = com.smashrank.smashrank_api.service.EloCalculator
                    .calculateDelta(1500, 1500, true, 0);
            int loserDelta = com.smashrank.smashrank_api.service.EloCalculator
                    .calculateDelta(1500, 1500, false, 0);
            assertEquals(0, winnerDelta + loserDelta,
                    "Total Elo should be conserved (zero-sum)");

            // Asymmetric ratings — verify with same K-factor
            int winDelta = com.smashrank.smashrank_api.service.EloCalculator
                    .calculateDelta(1300, 1700, true, 0);
            int lossDelta = com.smashrank.smashrank_api.service.EloCalculator
                    .calculateDelta(1700, 1300, false, 0);
            assertTrue(Math.abs(winDelta + lossDelta) <= 1,
                    "Total Elo should be approximately conserved (±1 for rounding)");
        }

        @Test
        @DisplayName("eloUpdate_higherRatedPlayerBeatsLower_smallerDelta")
        void eloUpdate_higherRatedPlayerBeatsLower_smallerDelta() {
            // Favored outcome: higher rated wins
            int favoredDelta = com.smashrank.smashrank_api.service.EloCalculator
                    .calculateDelta(1800, 1200, true, 0);
            // Baseline: equal rated players
            int evenDelta = com.smashrank.smashrank_api.service.EloCalculator
                    .calculateDelta(1500, 1500, true, 0);

            assertTrue(favoredDelta < evenDelta,
                    "Higher rated player winning should yield smaller delta than equal ratings");
            assertTrue(favoredDelta > 0,
                    "Even a favored win should still gain some Elo");
        }

        @Test
        @DisplayName("eloUpdate_lowerRatedPlayerBeatsHigher_largerDelta")
        void eloUpdate_lowerRatedPlayerBeatsHigher_largerDelta() {
            // Upset: lower rated wins
            int upsetDelta = com.smashrank.smashrank_api.service.EloCalculator
                    .calculateDelta(1200, 1800, true, 0);
            // Baseline: equal rated players
            int evenDelta = com.smashrank.smashrank_api.service.EloCalculator
                    .calculateDelta(1500, 1500, true, 0);

            assertTrue(upsetDelta > evenDelta,
                    "Lower rated player winning should yield larger delta than equal ratings");
        }

        @Test
        @DisplayName("eloUpdate_disputedMatch_noEloChange")
        void eloUpdate_disputedMatch_noEloChange() {
            // Set up a confirm scenario where players disagree
            String matchId = "match-dispute-elo";
            Match match = TestFixtures.buildMatch("playerA", "playerB");
            ReflectionTestUtils.setField(match, "id", matchId);

            pendingReports.put(matchId,
                    new PendingReport("playerA", "playerA"));
            when(matchRepository.findById(matchId)).thenReturn(Optional.of(match));

            // playerB confirms with different winner → DISPUTED
            controller.confirmResult(
                    new ConfirmRequest(matchId, "playerB", "playerB"));

            // EloService must NOT have been called
            verify(eloService, never()).processMatchResult(any());
            assertEquals("DISPUTED", match.getStatus());
        }
    }
}
