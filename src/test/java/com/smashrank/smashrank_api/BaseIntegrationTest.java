package com.smashrank.smashrank_api;

import com.smashrank.smashrank_api.controller.MatchController;
import com.smashrank.smashrank_api.service.PoolService;
import com.smashrank.util.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Base class for all Phase 2 integration tests.
 *
 * Boots the full Spring context once per concrete test class with real HTTP,
 * real STOMP WebSocket connections, and real databases via Testcontainers.
 *
 * Usage in concrete test classes:
 * <pre>
 *   class MyIntegrationTest extends BaseIntegrationTest {
 *
 *     {@literal @}Test
 *     void someTest() throws Exception {
 *       PlayerSession p1 = connectAsPlayer("player1");
 *       PlayerSession p2 = connectAsPlayer("player2");
 *
 *       httpPost("/api/matches/invite",
 *           Map.of("challengerUsername", "player1", "targetUsername", "player2"),
 *           p1.token());
 *
 *       String inviteJson = p2.inviteQueue().poll(2, TimeUnit.SECONDS);
 *       assertNotNull(inviteJson);
 *     }
 *   }
 * </pre>
 *
 * JSON parsing: inject {@code @Autowired ObjectMapper objectMapper} in your
 * test class and call {@code objectMapper.readValue(json, MatchUpdateEvent.class)}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class BaseIntegrationTest {

    // =========================================================================
    // Testcontainers — started once, shared across all tests in the suite
    // =========================================================================

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    /**
     * Override datasource and Redis properties with Testcontainers values.
     * This runs before the Spring context is created, taking highest priority
     * over both application.properties and application-test.properties.
     */
    @DynamicPropertySource
    static void configureTestContainerProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL — replaces the ${PGHOST}:${PGPORT}/... pattern entirely
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // Redis — override host/port; no auth on the test container
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.username", () -> "");
        registry.add("spring.data.redis.password", () -> "");
    }

    // =========================================================================
    // Injected Spring beans
    // =========================================================================

    @LocalServerPort
    protected int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MatchController matchController;

    @Autowired
    private PoolService poolService;

    // Tracks all STOMP sessions opened by this test class so they can be
    // closed after each test without leaking connections.
    private final List<StompSession> openSessions = new ArrayList<>();

    // =========================================================================
    // Per-test state reset
    // =========================================================================

    /**
     * Before each test: wipe DB tables, flush Redis, and clear MatchController's
     * in-memory state maps. This ensures every test starts from a clean slate.
     *
     * Note: DataSeeder runs once at context start. After the first @BeforeEach
     * truncation the seed data is gone, which is intentional — tests create their
     * own accounts via connectAsPlayer().
     */
    @BeforeEach
    void resetState() {
        // Wipe all application data (CASCADE handles FK ordering)
        jdbcTemplate.execute(
                "TRUNCATE TABLE matches, player_character_stats, players, refresh_tokens, users " +
                "RESTART IDENTITY CASCADE"
        );

        // Flush Redis pool state
        poolService.flushPool();

        // Clear MatchController's in-memory maps
        ((ConcurrentHashMap<?, ?>) ReflectionTestUtils.getField(matchController, "playerLocks")).clear();
        ((ConcurrentHashMap<?, ?>) ReflectionTestUtils.getField(matchController, "pendingReports")).clear();
        ((ConcurrentHashMap<?, ?>) ReflectionTestUtils.getField(matchController, "pendingRematches")).clear();
    }

    /**
     * After each test: gracefully close all STOMP connections opened during
     * the test. Prevents connection leaks between tests.
     */
    @AfterEach
    void closeWebSocketSessions() {
        for (StompSession session : openSessions) {
            try {
                if (session.isConnected()) {
                    session.disconnect();
                }
            } catch (Exception ignored) {
                // Best-effort cleanup
            }
        }
        openSessions.clear();
    }

    // =========================================================================
    // connectAsPlayer — register + connect WebSocket
    // =========================================================================

    /**
     * Register a player via the auth API, then open an authenticated STOMP
     * WebSocket connection subscribed to both message queues.
     *
     * <p>The returned {@link PlayerSession} contains:
     * <ul>
     *   <li>{@code token} — JWT for HTTP calls</li>
     *   <li>{@code matchQueue} — receives raw JSON from {@code /user/queue/match-updates}</li>
     *   <li>{@code inviteQueue} — receives raw JSON from {@code /user/queue/invites}</li>
     * </ul>
     *
     * <p>Messages are raw JSON strings. Parse them in your test with:
     * <pre>
     *   MatchUpdateEvent event = objectMapper.readValue(
     *       session.matchQueue().poll(2, SECONDS), MatchUpdateEvent.class);
     * </pre>
     *
     * @param username the username to register (must be unique within the test)
     * @throws Exception if registration or WebSocket connection fails
     */
    protected PlayerSession connectAsPlayer(String username) throws Exception {
        // 1. Register the player and retrieve JWT
        RestTemplate restTemplate = new RestTemplate();
        String registerUrl = "http://localhost:" + port + "/api/auth/register";

        ResponseEntity<Map> authResponse = restTemplate.postForEntity(
                registerUrl,
                Map.of("username", username, "password", TestFixtures.DEFAULT_PASSWORD),
                Map.class
        );

        if (!authResponse.getStatusCode().is2xxSuccessful() || authResponse.getBody() == null) {
            throw new IllegalStateException(
                    "Failed to register player '" + username + "': " + authResponse.getStatusCode());
        }

        String token = (String) authResponse.getBody().get("accessToken");
        if (token == null) {
            throw new IllegalStateException("No accessToken in register response for player: " + username);
        }

        // 2. Connect STOMP WebSocket — JWT passed as ?token= query param
        BlockingQueue<String> matchQueue = new LinkedBlockingQueue<>();
        BlockingQueue<String> inviteQueue = new LinkedBlockingQueue<>();

        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new StringMessageConverter());

        String wsUrl = "ws://localhost:" + port + "/ws-smashrank?token=" + token;
        StompSession session = stompClient
                .connectAsync(wsUrl, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

        // 3. Subscribe to both user queues
        session.subscribe("/user/queue/match-updates", new JsonQueueHandler(matchQueue));
        session.subscribe("/user/queue/invites", new JsonQueueHandler(inviteQueue));

        // Brief yield so the server processes the SUBSCRIBE frames before the
        // test sends its first HTTP request.
        Thread.sleep(100);

        openSessions.add(session);
        return new PlayerSession(username, token, session, matchQueue, inviteQueue);
    }

    // =========================================================================
    // HTTP helpers
    // =========================================================================

    /**
     * POST to an application endpoint with a Bearer token.
     * Returns the raw response (no exception on 4xx/5xx — check status code).
     */
    protected ResponseEntity<String> httpPost(String path, Object body, String token) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        return restTemplate.exchange(
                "http://localhost:" + port + path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );
    }

    /**
     * POST without a Bearer token (for public endpoints like /api/auth/*).
     */
    protected ResponseEntity<String> httpPost(String path, Object body) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        return restTemplate.exchange(
                "http://localhost:" + port + path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Represents one connected player. Carries the auth token (for HTTP) and
     * the STOMP queues (for asserting WebSocket events).
     *
     * <p>Both queues hold raw JSON strings. Use {@code objectMapper.readValue()}
     * in your test class to convert them to typed objects.
     */
    public record PlayerSession(
            String username,
            String token,
            StompSession stompSession,
            /** Raw JSON strings from /user/queue/match-updates */
            BlockingQueue<String> matchQueue,
            /** Raw JSON strings from /user/queue/invites */
            BlockingQueue<String> inviteQueue
    ) {}

    /**
     * STOMP frame handler that appends the raw JSON payload (as a String) to
     * a {@link BlockingQueue}. Used for both the match-updates and invites queues.
     */
    private record JsonQueueHandler(BlockingQueue<String> queue) implements StompFrameHandler {

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            if (payload instanceof String json) {
                queue.offer(json);
            }
        }
    }
}
