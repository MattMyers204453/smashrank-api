# smashrank-api

Spring Boot 4.0.1 / Java 21 backend for SmashRank. See `../CLAUDE.md` for full project architecture.

## Commands

```bash
# Run locally
./mvnw spring-boot:run

# Run with dev profile
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Build
./mvnw clean package -DskipTests

# Run tests
./mvnw test

# Deploy — Railway auto-deploys on push to main
git push origin main
```

## Package Structure

Base package: `com.smashrank.smashrank_api`

```
controller/    # REST endpoints + WebSocket message handling
service/       # Business logic (AuthService, PoolService, UserService, EloService)
model/         # JPA entities (User, Player, Match, RefreshToken, PoolPlayer)
repository/    # Spring Data JPA repositories
config/        # WebSocketConfig, SecurityConfig, DataSeeder
security/      # JwtUtil, JwtAuthFilter
```

## Key Files

| File | Purpose |
|------|---------|
| `MatchController.java` | Match endpoints, in-memory lock/report/rematch maps, STOMP notifications |
| `AuthController.java` | Register, login, refresh endpoints (`/api/auth/**`) |
| `AuthService.java` | Auth business logic, BCrypt hashing, token generation, input validation |
| `UserService.java` | userId ↔ username resolution (migration helper) |
| `PoolController.java` / `PoolService.java` | Redis-backed active pool with double-index pattern |
| `WebSocketConfig.java` | STOMP config, `UserHandshakeHandler` (JWT + legacy handshake) |
| `SecurityConfig.java` | Spring Security — CORS, CSRF disabled, stateless sessions. Currently `permitAll()`. |
| `JwtUtil.java` | Token generation/validation (HMAC-SHA256 via JJWT) |
| `JwtAuthFilter.java` | `OncePerRequestFilter` — reads Bearer header, validates, sets SecurityContext |
| `DataSeeder.java` | Seeds 4 test users + linked players on empty DB |

## API Endpoints

### Auth (`/api/auth`)
- `POST /register` — Create user + linked player. Returns tokens.
- `POST /login` — Validate credentials. Returns tokens.
- `POST /refresh` — Rotate refresh token. Returns new token pair.

### Pool (`/api/pool`)
- `POST /check-in?username=&character=&elo=` — Enter active pool
- `POST /check-out?username=&character=&elo=` — Leave pool
- `GET /search?query=` — Prefix search active players
- `GET /all` — List all checked-in players

### Matches (`/api/matches`)
- `POST /invite` — Lock both players, send STOMP invite
- `POST /accept` — Validate locks, create Match in Postgres, notify STARTED
- `POST /decline` — Release locks, notify
- `POST /report` — First player submits claim, stored in pendingReports
- `POST /confirm` — Second player's claim. Agree → COMPLETED. Disagree → DISPUTED. Both → REMATCH_OFFERED.
- `POST /rematch` — Accept/decline within 15s. Both accept → new match.

### WebSocket
- Endpoint: `wss://.../ws-smashrank?token=<JWT>` (or `?username=` legacy)
- Subscriptions: `/user/queue/invites`, `/user/queue/match-updates`

## Code Conventions

- Entities use JPA annotations with Lombok `@Getter/@Setter` where appropriate
- DTOs are Java records (e.g., `AuthRequest`, `InviteRequest`, `ConfirmRequest`)
- `@CreationTimestamp` / `@UpdateTimestamp` for audit fields
- Controller methods return `ResponseEntity<>` with appropriate status codes
- STOMP messages via `SimpMessagingTemplate.convertAndSendToUser(username, dest, payload)`
- In-memory maps use `ConcurrentHashMap` — single-instance only

## Testing

- **HTTP:** REST Client `.http` files in IntelliJ (version controlled)
- **WebSocket:** `WebSocketTester.html` with Stomp.js for STOMP flows
- **Multi-client:** Two browser tabs or Simulator + physical device
- **Seed data:** 4 accounts auto-seeded via `DataSeeder` (all password: `password123`)
- **Stress testing:** Gatling planned for WebSocket concurrency + DB load

## Dependencies of Note

- `spring-boot-starter-security` — Spring Security 7 config
- `jjwt-api` / `jjwt-impl` / `jjwt-gson` (0.13.0) — JWT. Uses Gson serializer, NOT Jackson (Jackson 3.0 incompatibility in Spring Boot 4)
- `postgresql` — runtime driver
- `spring-boot-starter-data-redis` — Redis client
- `spring-boot-starter-websocket` — STOMP support
- `lombok` — boilerplate reduction

## Gotchas

- Virtual threads are enabled (`spring.threads.virtual.enabled=true`). Don't use `synchronized` blocks on virtual threads — use `ReentrantLock` or pessimistic DB locks instead.
- `playerLocks` map uses **username** as key (not userId) — Phase 5 migrates to userId.
- Match entity has both username and UUID columns. Always populate both when creating matches. Phase 5 removes username columns.
- `SecurityConfig` is `permitAll()` right now. Don't assume endpoints are secured.
- DataSeeder runs on every startup but checks `userRepository.count() == 0` first.