package com.kanbancord_api.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanbancord_api.integration.config.TestcontainersConfiguration;
import com.kanbancord_api.security.JwtTokenService;
import com.kanbancord_api.session.UserSessionService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real HTTP + WebSocket API against a real Postgres (Testcontainers), the way the bot and
 * the web client do: the server is bootstrapped through the internal sync API with the bot token, and
 * users call the public API with JWTs. Secrets are generated per run; no external services are used.
 */
@Tag("integration")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndApiIntegrationTest {

    private static final String BOT_TOKEN = randomSecret();
    private static final String JWT_SECRET = randomSecret();
    private static final AtomicLong SERVER_IDS = new AtomicLong(900_000_000_000L);

    private static final long VIEW_CHANNEL = 1L << 10;
    private static final long SEND_MESSAGES = 1L << 11;
    private static final long MANAGE_MESSAGES = 1L << 13;

    private static final long OWNER = 1_000L;
    private static final long MOD = 1_001L;
    private static final long MEMBER = 1_002L;
    private static final long OTHER = 1_003L;
    private static final long NEWBIE = 1_004L;

    private static final String WEB_ORIGIN = "https://kanbancord.com";
    private static final Map<String, Boolean> ALL_FEATURES = Map.of("LABELS", true, "PRIORITIES", true,
            "ASSIGNEES", true, "COMMENTS", true, "DUE_DATES", true, "PERMISSIONS", true);

    /** Stands in for Imgur, so uploads can be checked without a real account. */
    private static final FakeImgur FAKE_IMGUR = new FakeImgur();

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("kanbancord.auth.jwt.secret", () -> JWT_SECRET);
        registry.add("kanbancord.internal-sync.bot-token", () -> sha256Hex(BOT_TOKEN));
        registry.add("kanbancord.imgur.client-id", () -> "test-client-id");
        registry.add("kanbancord.imgur.api-base-url", FAKE_IMGUR::baseUrl);
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JwtTokenService jwtTokenService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private javax.sql.DataSource dataSource;
    @Autowired
    private UserSessionService userSessionService;

    /** One signed-in session per user, reused across calls. */
    private final Map<Long, String> accessTokens = new ConcurrentHashMap<>();

    private final HttpClient http = HttpClient.newHttpClient();

    // ── Default rules: the everyday workflow ─────────────────────────────────

    @Test
    void defaultRules_supportEverydayWorkflow_andEnforceRoleTiers() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Sprint");
        assertEquals(0, call("GET", w.path("/permissions?scopeType=BOARD&scopeId=" + board), OWNER, null)
                .json().size(), "new boards inherit server rules instead of copying them");

        long todo = w.column(board, "Todo");
        long doing = w.column(board, "Doing");

        long task = w.createTask(MEMBER, board, todo, "Write docs");
        assertEquals(200, w.updateTask(MEMBER, board, task, "Write docs", doing).status(), "members can move tasks");
        assertEquals(403, w.updateTask(MEMBER, board, task, "Renamed", doing).status(), "members cannot edit tasks");
        assertEquals(200, w.updateTask(MOD, board, task, "Renamed", doing).status(), "mods can edit tasks");

        String assignments = w.boardPath(board, "/tasks/" + task + "/assignments");
        assertEquals(201, call("POST", assignments, MEMBER, Map.of("taskId", task, "userId", MEMBER)).status());
        assertEquals(403, call("POST", assignments, MEMBER, Map.of("taskId", task, "userId", OTHER)).status(),
                "assigning others needs ASSIGN_TASK_OTHERS");
        assertEquals(201, call("POST", assignments, MOD, Map.of("taskId", task, "userId", OTHER)).status());

        String comments = w.boardPath(board, "/tasks/" + task + "/comments");
        long ownComment = call("POST", comments, MEMBER, Map.of("taskId", task, "content", "mine"))
                .expect(201).json().get("commentId").asLong();
        long modComment = call("POST", comments, MOD, Map.of("taskId", task, "content", "mod's"))
                .expect(201).json().get("commentId").asLong();
        assertEquals(200, call("PUT", comments + "/" + ownComment, MEMBER,
                Map.of("taskId", task, "content", "edited")).status(), "authors edit their own comments");
        assertEquals(403, call("PUT", comments + "/" + modComment, MEMBER,
                Map.of("taskId", task, "content", "hijack")).status(), "others' comments need moderation rights");

        // Discord ids go out as strings: real ones are too large for a JavaScript number.
        JsonNode comment = call("GET", comments + "/" + ownComment, MEMBER, null).expect(200).json();
        assertTrue(comment.get("userId").isTextual(), comment.toString());
        JsonNode snapshot = w.snapshot(MEMBER, board);
        assertTrue(snapshot.get("tasks").get(0).get("createdBy").isTextual());
        assertTrue(snapshot.get("assignments").get(0).get("userId").isTextual());
    }

    // ── Board overrides and EDIT_BOARD_PERMISSIONS ─────────────────────────────

    @Test
    void boardPermissionEditor_canMakeBoardPrivate_andBoardOverridesServer() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Staff only");
        w.createRule(OWNER, "BOARD", board, "USER", OTHER, "EDIT_BOARD_PERMISSIONS", "ALLOW").expect(201);
        w.createRule(OWNER, "BOARD", board, "USER", OTHER, "VIEW_BOARD", "ALLOW").expect(201);

        long denyEveryone = w.createRule(OTHER, "BOARD", board, "DISCORD_PERMISSION", VIEW_CHANNEL,
                "VIEW_BOARD", "DENY").expect(201).json().get("id").asLong();
        w.createRule(OTHER, "BOARD", board, "ROLE", w.modsRole, "VIEW_BOARD", "ALLOW").expect(201);

        assertEquals(403, call("GET", w.path("/boards/" + board), MEMBER, null).status());
        assertEquals(403, call("GET", w.boardPath(board, "/tasks"), MEMBER, null).status(),
                "a private board's tasks are hidden even though VIEW_TASK is inherited");
        assertEquals(403, call("GET", w.boardPath(board, "/task-assignments"), MEMBER, null).status());
        assertEquals(403, w.createTaskStatus(MEMBER, board, w.column(board, "Todo")),
                "no blind writes into a board you cannot see");
        assertFalse(w.visibleBoards(MEMBER).contains(board), "hidden boards are filtered from the list");
        assertEquals(200, call("GET", w.path("/boards/" + board), MOD, null).status());
        assertEquals(200, call("GET", w.path("/boards/" + board), OTHER, null).status());
        assertEquals(200, call("GET", w.path("/boards/" + board), OWNER, null).status(), "admins see everything");

        long otherBoard = w.createBoard(OWNER, "Public");
        assertTrue(w.visibleBoards(MEMBER).contains(otherBoard), "other boards still inherit the server rules");

        assertEquals(403, w.createRule(OTHER, "BOARD", board, "ROLE", w.membersRole, "DELETE_COLUMN", "ALLOW")
                .status(), "board permission editors cannot grant board-management keys");
        assertEquals(403, w.createRule(OTHER, "SERVER", w.serverId, "ROLE", w.membersRole, "VIEW_BOARD", "DENY")
                .status(), "board permission editors cannot change server rules");
        assertEquals(403, w.createRule(MOD, "BOARD", board, "ROLE", w.membersRole, "VIEW_BOARD", "ALLOW")
                .status(), "changing board rules needs EDIT_BOARD_PERMISSIONS");

        // Editing a rule in place (as the dashboard does) returns the updated rule.
        String rulePath = w.path("/permissions/" + denyEveryone);
        assertEquals(204, call("PATCH", rulePath + "/state", OTHER, Map.of("state", "ALLOW")).status());
        JsonNode edited = call("PUT", rulePath, OTHER, Map.of(
                "scopeType", "BOARD", "scopeId", board, "subjectType", "DISCORD_PERMISSION", "subjectId", VIEW_CHANNEL,
                "kanbanPermissionId", w.catalog.get("VIEW_BOARD"), "state", "DENY", "priority", 100))
                .expect(200).json();
        assertEquals("VIEW_BOARD", edited.get("kanbanPermissionKey").asText());
        assertEquals("DENY", edited.get("state").asText());

        assertEquals(204, call("DELETE", rulePath, OTHER, null).status());
        assertEquals(200, call("GET", w.path("/boards/" + board), MEMBER, null).status(), "reverting restores access");
    }

    @Test
    void resolution_denyWinsWithinLayer_rolesOverrideDiscord_userOverridesAll() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Rules");
        long todo = w.column(board, "Todo");

        w.createRule(OWNER, "SERVER", w.serverId, "ROLE", w.membersRole, "CREATE_TASK", "DENY").expect(201);
        assertEquals(403, w.createTaskStatus(MEMBER, board, todo), "role layer overrides the Discord layer");

        w.createRule(OWNER, "SERVER", w.serverId, "ROLE", w.modsRole, "CREATE_TASK", "ALLOW").expect(201);
        assertEquals(403, w.createTaskStatus(MOD, board, todo), "within a layer DENY beats ALLOW");

        w.createRule(OWNER, "SERVER", w.serverId, "USER", MOD, "CREATE_TASK", "ALLOW").expect(201);
        assertEquals(201, w.createTaskStatus(MOD, board, todo), "user rules override role rules");

        w.createRule(OWNER, "BOARD", board, "ROLE", w.membersRole, "CREATE_TASK", "ALLOW").expect(201);
        assertEquals(201, w.createTaskStatus(MEMBER, board, todo), "board rules override server rules");
    }

    // ── Identity and hardening ─────────────────────────────────────────────────

    @Test
    void identityComesOnlyFromTheToken() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Keep");

        assertEquals(401, call("GET", w.path("/boards/" + board), null, null).status());
        assertEquals(401, callRaw("GET", w.path("/boards/" + board), "Bearer not-a-jwt", null).status());
        assertEquals(403, call("DELETE", w.path("/boards/" + board + "?userId=" + OWNER), MEMBER, null).status(),
                "a userId parameter cannot impersonate another user");

        JsonNode created = call("POST", w.path("/boards"), OWNER,
                Map.of("name", "Spoof", "serverId", w.serverId, "createdBy", MEMBER)).expect(201).json();
        assertEquals(OWNER, created.get("createdBy").asLong(), "createdBy comes from the token");

        assertTrue(call("POST", w.path("/permissions/catalog"), OWNER, Map.of()).status() >= 400);
        assertTrue(call("POST", "/api/servers/" + w.serverId + "/members/1/roles", OWNER, Map.of()).status() >= 400);
        assertEquals(403, call("GET", w.path("/audit-logs"), MEMBER, null).status());

        // CORS: configured origins get a preflight grant, anything else does not.
        HttpResponse<String> allowedPreflight = preflight(w.path("/boards"), "https://kanbancord.com", "GET");
        assertEquals("https://kanbancord.com",
                allowedPreflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        // The web client archives boards and toggles rule states with PATCH.
        HttpResponse<String> patchPreflight = preflight(w.path("/boards/" + board + "/archive"),
                "https://kanbancord.com", "PATCH");
        assertEquals(200, patchPreflight.statusCode());
        assertTrue(patchPreflight.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("PATCH"));
        HttpResponse<String> foreignPreflight = preflight(w.path("/boards"), "https://evil.example", "GET");
        assertTrue(foreignPreflight.headers().firstValue("Access-Control-Allow-Origin").isEmpty());

        JsonNode health = call("GET", "/actuator/health", null, null).expect(200).json();
        assertEquals("UP", health.get("status").asText());
        assertFalse(health.has("components"), "health details are not public");
    }

    // ── Realtime ─────────────────────────────────────────────────────────────

    @Test
    void realtime_boardTopic_requiresViewBoard_andDeliversEvents() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Live");
        long todo = w.column(board, "Todo");
        w.createRule(OWNER, "BOARD", board, "DISCORD_PERMISSION", VIEW_CHANNEL, "VIEW_BOARD", "DENY").expect(201);
        w.createRule(OWNER, "BOARD", board, "ROLE", w.modsRole, "VIEW_BOARD", "ALLOW").expect(201);
        String topic = "/topic/servers/" + w.serverId + "/boards/" + board;

        // Control: the same member can subscribe to a board they can view and receives its events.
        long publicBoard = w.createBoard(OWNER, "Public live");
        long publicTodo = w.column(publicBoard, "Todo");
        RealtimeProbe memberOnPublic = RealtimeProbe.connect(this, MEMBER);
        memberOnPublic.subscribe("/topic/servers/" + w.serverId + "/boards/" + publicBoard);
        Thread.sleep(500);
        w.createTask(MOD, publicBoard, publicTodo, "Visible task");
        assertEquals("TASK_CREATED", memberOnPublic.next().get("eventType"));
        assertFalse(memberOnPublic.failure.isDone());

        // Without VIEW_BOARD on the private board, the subscription is refused and the session closed.
        RealtimeProbe member = RealtimeProbe.connect(this, MEMBER);
        member.subscribe(topic);
        String rejection = member.failure.get(10, TimeUnit.SECONDS);
        assertTrue(rejection.startsWith("ERROR frame"), rejection);

        RealtimeProbe mod = RealtimeProbe.connect(this, MOD);
        mod.subscribe(topic);
        Thread.sleep(500);
        w.createTask(MOD, board, todo, "Realtime task");
        Map<?, ?> event = mod.next();
        assertEquals("TASK_CREATED", event.get("eventType"));
    }

    @Test
    void realtime_privateBoardEvents_areWithheld_andAccessIsRecheckedPerEvent() throws Exception {
        World w = bootstrapServer();
        long secret = w.createBoard(OWNER, "Secret");
        long open = w.createBoard(OWNER, "Open");
        long openTodo = w.column(open, "Todo");
        String serverTopic = "/topic/servers/" + w.serverId;

        RealtimeProbe member = RealtimeProbe.connect(this, MEMBER);
        member.subscribe(serverTopic);
        member.subscribe(w.topic(open));
        RealtimeProbe mod = RealtimeProbe.connect(this, MOD);
        mod.subscribe(serverTopic);
        RealtimeProbe other = RealtimeProbe.connect(this, OTHER);
        other.subscribe(w.topic(open));
        Thread.sleep(500);

        // Make "Secret" visible to Mods only, then change it.
        w.createRule(OWNER, "BOARD", secret, "DISCORD_PERMISSION", VIEW_CHANNEL, "VIEW_BOARD", "DENY").expect(201);
        w.createRule(OWNER, "BOARD", secret, "ROLE", w.modsRole, "VIEW_BOARD", "ALLOW").expect(201);
        w.renameBoard(secret, "Secret plans");
        w.renameBoard(open, "Open plans");

        // The server topic still carries the private board's events to Mods...
        assertTrue(mod.drain().stream().anyMatch(e -> "BOARD_UPDATED".equals(e.get("eventType"))
                && ((Number) e.get("boardId")).longValue() == secret));
        // ...but a member only hears about the board they can see.
        List<Map<?, ?>> memberEvents = member.drain();
        assertTrue(memberEvents.stream().anyMatch(e -> ((Number) e.get("boardId")).longValue() == open));
        assertTrue(memberEvents.stream().noneMatch(e -> ((Number) e.get("boardId")).longValue() == secret),
                memberEvents.toString());

        // Losing access to a board stops its events on an existing subscription.
        w.createRule(OWNER, "BOARD", open, "USER", MEMBER, "VIEW_BOARD", "DENY").expect(201);
        other.drain();
        w.createTask(MOD, open, openTodo, "After revocation");
        assertEquals("TASK_CREATED", other.next().get("eventType"));
        List<Map<?, ?>> afterRevocation = member.drain();
        assertTrue(afterRevocation.isEmpty(), afterRevocation.toString());

        // The rules of a board are as private as the board.
        JsonNode memberRules = call("GET", w.path("/permissions"), MEMBER, null).expect(200).json();
        for (JsonNode rule : memberRules) {
            assertFalse("BOARD".equals(rule.get("scopeType").asText()) && rule.get("scopeId").asLong() == secret,
                    rule.toString());
        }
        call("GET", w.path("/permissions?scopeType=BOARD&scopeId=" + secret), MEMBER, null).expect(403);
        assertEquals(2, call("GET", w.path("/permissions?scopeType=BOARD&scopeId=" + secret), MOD, null)
                .expect(200).json().size());
    }

    @Test
    void realtime_losingAccess_endsTheSubscription_andTellsTheClient() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Shrinking");
        long todo = w.column(board, "Todo");

        RealtimeProbe member = RealtimeProbe.connect(this, MEMBER);
        member.subscribe("/user/queue/session");
        member.subscribe(w.topic(board));
        RealtimeProbe other = RealtimeProbe.connect(this, OTHER);
        other.subscribe("/user/queue/session");
        other.subscribe("/topic/servers/" + w.serverId);
        Thread.sleep(500);

        w.createRule(OWNER, "BOARD", board, "USER", MEMBER, "VIEW_BOARD", "DENY").expect(201);
        Map<?, ?> notice = member.next();
        assertEquals("SUBSCRIPTION_REVOKED", notice.get("type"));
        assertEquals("ACCESS_LOST", notice.get("reason"));
        assertEquals(w.topic(board), notice.get("destination"));

        w.createTask(MOD, board, todo, "Unseen");
        List<Map<?, ?>> afterRevocation = member.drain();
        assertTrue(afterRevocation.isEmpty(), afterRevocation.toString());

        // Leaving the Discord server ends the server-wide subscription too.
        other.drain();
        assertEquals(204, sync("DELETE", "/api/internal/sync/servers/" + w.serverId + "/members/" + OTHER, Map.of()));
        List<Map<?, ?>> otherNotices = other.drain();
        assertTrue(otherNotices.stream().anyMatch(e -> "SUBSCRIPTION_REVOKED".equals(e.get("type"))
                && ("/topic/servers/" + w.serverId).equals(e.get("destination"))), otherNotices.toString());
        assertFalse(member.failure.isDone(), "losing access to one topic keeps the connection open");
    }

    @Test
    void realtime_labelChanges_reachTheBoard_andTheSnapshotCarriesLabels() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Labelled");
        long task = w.createTask(MOD, board, w.column(board, "Todo"), "Tag me");

        RealtimeProbe member = RealtimeProbe.connect(this, MEMBER);
        member.subscribe(w.topic(board));
        Thread.sleep(500);

        long label = call("POST", w.boardPath(board, "/labels"), OWNER,
                Map.of("boardId", board, "name", "bug", "color", "#ff0000")).expect(201).json().get("labelId").asLong();
        assertEquals("LABEL_CREATED", member.next().get("eventType"));
        call("PUT", w.boardPath(board, "/labels/" + label), OWNER,
                Map.of("boardId", board, "name", "defect", "color", "#ff0000")).expect(200);
        assertEquals("LABEL_UPDATED", member.next().get("eventType"));
        long taskLabel = call("POST", w.boardPath(board, "/tasks/" + task + "/labels"), OWNER,
                Map.of("taskId", task, "labelId", label)).expect(201).json().get("id").asLong();
        assertEquals("TASK_LABEL_ADDED", member.next().get("eventType"));

        JsonNode snapshot = w.snapshot(MEMBER, board);
        assertEquals("defect", snapshot.get("labels").get(0).get("name").asText());
        assertEquals(label, snapshot.get("taskLabels").get(0).get("labelId").asLong());

        call("DELETE", w.boardPath(board, "/tasks/" + task + "/labels/" + taskLabel), OWNER, null).expect(204);
        assertEquals("TASK_LABEL_REMOVED", member.next().get("eventType"));
        call("DELETE", w.boardPath(board, "/labels/" + label), OWNER, null).expect(204);
        assertEquals("LABEL_DELETED", member.next().get("eventType"));
        assertTrue(auditLog(w).stream().anyMatch(e -> "LABEL_DELETED".equals(e.get("action").asText())),
                "label changes are audited like every other change");
    }

    @Test
    void realtime_discordSyncChanges_areAnnouncedOnTheServerTopic_butNotAudited() throws Exception {
        World w = bootstrapServer();
        RealtimeProbe member = RealtimeProbe.connect(this, MEMBER);
        member.subscribe("/topic/servers/" + w.serverId);
        Thread.sleep(500);
        int auditEntries = auditLog(w).size();

        assertEquals(204, sync("PUT", "/api/internal/sync/servers/" + w.serverId + "/roles/" + w.modsRole,
                Map.of("name", "Moderators", "position", 2, "discordPermissions", VIEW_CHANNEL | SEND_MESSAGES)));
        Map<?, ?> role = member.next();
        assertEquals("ROLE_SYNCED", role.get("eventType"));
        assertEquals("ROLE", role.get("entityType"));

        assertEquals(204, sync("PUT", "/api/internal/sync/servers/" + w.serverId + "/members/" + MEMBER + "/roles",
                Map.of("roleIds", List.of(w.membersRole, w.modsRole))));
        assertEquals("MEMBER_SYNCED", member.next().get("eventType"));

        assertEquals(204, sync("PUT", "/api/internal/sync/servers/" + w.serverId,
                Map.of("name", "Renamed", "ownerId", OWNER, "ownerUsername", "owner")));
        assertEquals("SERVER_SYNCED", member.next().get("eventType"));

        assertEquals(auditEntries, auditLog(w).size(), "the audit log records what people did in KanbanCord");
    }

    @Test
    void realtime_userQueue_reachesOnlyThatUser() throws Exception {
        World w = bootstrapServer();
        RealtimeProbe mine = RealtimeProbe.connect(this, MEMBER);
        mine.subscribe("/user/queue/me");
        RealtimeProbe someoneElse = RealtimeProbe.connect(this, MOD);
        someoneElse.subscribe("/user/queue/me");
        Thread.sleep(500);

        call("PUT", w.path("/users/" + MEMBER), MEMBER, Map.of("preferences", Map.of("theme", "dark"))).expect(200);
        Map<?, ?> event = mine.next();
        assertEquals("PROFILE_UPDATED", event.get("eventType"));
        assertEquals("dark", ((Map<?, ?>) ((Map<?, ?>) event.get("payload")).get("preferences")).get("theme"));

        userSessionService.start(MEMBER, "another device");
        assertEquals("SESSIONS_CHANGED", mine.next().get("eventType"));

        List<Map<?, ?>> leaked = someoneElse.drain();
        assertTrue(leaked.isEmpty(), leaked.toString());
    }

    @Test
    void preferences_mergeByTopLevelKey_areLimitedInSize_andReachTheUsersOtherTabs() throws Exception {
        World w = bootstrapServer();
        RealtimeProbe otherTab = RealtimeProbe.connect(this, MEMBER);
        otherTab.subscribe("/user/queue/me");
        Thread.sleep(500);

        call("PATCH", "/api/me/preferences", MEMBER, Map.of("theme", Map.of("preset", "high-contrast"))).expect(200);
        Map<?, ?> event = otherTab.next();
        assertEquals("PROFILE_UPDATED", event.get("eventType"));

        call("PATCH", "/api/me/preferences", MEMBER, Map.of("simpleView", Map.of("COMMENTS", true))).expect(200);
        JsonNode saved = call("GET", "/api/me/preferences", MEMBER, null).expect(200).json();
        assertEquals("high-contrast", saved.at("/theme/preset").asText(), "saving one part keeps the others");
        assertTrue(saved.at("/simpleView/COMMENTS").asBoolean());
        assertEquals("high-contrast", call("GET", "/api/me", MEMBER, null).json().at("/preferences/theme/preset").asText());

        Map<String, Object> removeTheme = new HashMap<>();
        removeTheme.put("theme", null);
        assertFalse(call("PATCH", "/api/me/preferences", MEMBER, removeTheme).expect(200).json().has("theme"));
        assertEquals(400, call("PATCH", "/api/me/preferences", MEMBER, Map.of("junk", "x".repeat(40_000))).status());
        assertFalse(call("GET", "/api/me/preferences", OWNER, null).expect(200).json().has("simpleView"),
                "preferences are per user");
    }

    // ── Sessions ─────────────────────────────────────────────────────────────

    @Test
    void sessions_refreshRotatesTheCookie_andOnlyTheWebAppMayUseIt() throws Exception {
        bootstrapServer();
        String cookie = userSessionService.start(OWNER, "browser").refreshToken();

        assertEquals(403, session("/api/auth/refresh", cookie, null).statusCode(), "no Origin header");
        assertEquals(403, session("/api/auth/refresh", cookie, "https://evil.example").statusCode());

        HttpResponse<String> refreshed = session("/api/auth/refresh", cookie, WEB_ORIGIN);
        assertEquals(200, refreshed.statusCode(), refreshed.body());
        String rotated = refreshCookie(refreshed);
        assertTrue(rotated != null && !rotated.equals(cookie), "the cookie is rotated");
        String setCookie = refreshed.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Strict")
                && setCookie.contains("Path=/api/auth"), setCookie);
        JsonNode body = objectMapper.readTree(refreshed.body());
        assertEquals(OWNER, body.get("user").get("userId").asLong());
        String accessToken = body.get("accessToken").asText();
        assertEquals(200, callRaw("GET", "/api/me", "Bearer " + accessToken, null).status());

        // Another tab refreshing with the token just replaced still gets in, and is handed the current
        // token, which also repairs a browser that lost the response carrying it.
        HttpResponse<String> lateTab = session("/api/auth/refresh", cookie, WEB_ORIGIN);
        assertEquals(200, lateTab.statusCode());
        assertEquals(rotated, refreshCookie(lateTab));

        // Presented again once the grace period is over, the old token counts as stolen: the session ends.
        jdbcTemplate.update("UPDATE user_sessions SET refreshed_at = now() - interval '10 minutes' "
                + "WHERE refresh_token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')", rotated);
        assertEquals(401, session("/api/auth/refresh", cookie, WEB_ORIGIN).statusCode());
        assertEquals(401, session("/api/auth/refresh", rotated, WEB_ORIGIN).statusCode());
        assertEquals(401, callRaw("GET", "/api/me", "Bearer " + accessToken, null).status(),
                "access tokens of a revoked session stop working before they expire");

        assertEquals(401, session("/api/auth/refresh", null, WEB_ORIGIN).statusCode());
        assertEquals(401, callRaw("GET", "/api/me", "Bearer " + jwtTokenService.issueToken(OWNER, UUID.randomUUID()), null)
                .status(), "a token naming an unknown session is rejected");
    }

    @Test
    void sessions_logoutRevokesTheSession_andClosesItsRealtimeConnections() throws Exception {
        World w = bootstrapServer();
        UserSessionService.IssuedSession laptop = userSessionService.start(MOD, "laptop");
        UserSessionService.IssuedSession phone = userSessionService.start(MOD, "phone");
        String laptopToken = "Bearer " + jwtTokenService.issueToken(MOD, laptop.session().getSessionId());
        String phoneToken = "Bearer " + jwtTokenService.issueToken(MOD, phone.session().getSessionId());

        RealtimeProbe laptopLive = RealtimeProbe.connect(this, laptopToken);
        laptopLive.subscribe("/topic/servers/" + w.serverId);
        RealtimeProbe phoneLive = RealtimeProbe.connect(this, phoneToken);
        phoneLive.subscribe("/topic/servers/" + w.serverId);
        Thread.sleep(500);

        JsonNode sessions = callRaw("GET", "/api/me/sessions", laptopToken, null).expect(200).json();
        assertTrue(sessions.size() >= 2);
        long current = 0;
        for (JsonNode listed : sessions) {
            if (listed.get("current").asBoolean()) {
                current++;
                assertEquals(laptop.session().getSessionId().toString(), listed.get("sessionId").asText());
            }
        }
        assertEquals(1, current);

        HttpResponse<String> logout = session("/api/auth/logout", laptop.refreshToken(), WEB_ORIGIN);
        assertEquals(204, logout.statusCode());
        assertTrue(logout.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"));
        assertEquals(401, callRaw("GET", "/api/me", laptopToken, null).status());
        assertTrue(laptopLive.failure.get(10, TimeUnit.SECONDS).startsWith("transport"),
                "the signed-out session's WebSocket is closed");
        assertEquals(401, callRaw("POST", "/api/realtime/tickets", laptopToken, null).status());

        assertEquals(200, callRaw("GET", "/api/me", phoneToken, null).status(), "other sessions stay signed in");
        assertFalse(phoneLive.failure.isDone());

        // "Sign out everywhere else" from a third session ends the phone's.
        UserSessionService.IssuedSession desktop = userSessionService.start(MOD, "desktop");
        String desktopToken = "Bearer " + jwtTokenService.issueToken(MOD, desktop.session().getSessionId());
        assertEquals(204, callRaw("DELETE", "/api/me/sessions", desktopToken, null).status());
        assertEquals(401, callRaw("GET", "/api/me", phoneToken, null).status());
        assertTrue(phoneLive.failure.get(10, TimeUnit.SECONDS).startsWith("transport"));
        assertEquals(200, callRaw("GET", "/api/me", desktopToken, null).status());

        // Sessions of other users cannot be revoked.
        UUID ownerSession = userSessionService.start(OWNER, "x").session().getSessionId();
        assertEquals(404, callRaw("DELETE", "/api/me/sessions/" + ownerSession, desktopToken, null).status());
    }

    // ── Moves and the board snapshot ────────────────────────────────────────────

    @Test
    void moves_renumberColumns_needOnlyMovePermissions_andAreOneAuditEntry() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Moves");
        long todo = w.column(board, "Todo");
        long doing = w.column(board, "Doing");
        long a = w.createTask(MOD, board, todo, "A");
        long b = w.createTask(MOD, board, todo, "B");
        long c = w.createTask(MOD, board, todo, "C");
        // MOD may move but not edit tasks or columns.
        w.createRule(OWNER, "BOARD", board, "USER", MOD, "EDIT_TASK", "DENY").expect(201);
        w.createRule(OWNER, "BOARD", board, "USER", MOD, "EDIT_COLUMN", "DENY").expect(201);
        w.createRule(OWNER, "BOARD", board, "USER", MOD, "MOVE_COLUMN", "ALLOW").expect(201);

        w.moveTask(MOD, board, c, todo, 0).expect(200);
        assertEquals(Map.of(todo, List.of(c, a, b)), w.tasksByColumn(board));

        JsonNode moved = w.moveTask(MOD, board, a, doing, 5).expect(200).json();
        assertEquals(doing, moved.get("columnId").asLong());
        assertEquals(Map.of(todo, List.of(c, b), doing, List.of(a)), w.tasksByColumn(board));

        // Renaming (resending the current position) is not a move; changing the position is.
        w.createRule(OWNER, "BOARD", board, "USER", MEMBER, "EDIT_COLUMN", "ALLOW").expect(201);
        JsonNode todoColumn = call("GET", w.boardPath(board, "/columns/" + todo), OWNER, null).expect(200).json();
        call("PUT", w.boardPath(board, "/columns/" + todo), MEMBER, Map.of("boardId", board, "name", "To do",
                "position", todoColumn.get("position").decimalValue())).expect(200);
        assertEquals(403, call("PUT", w.boardPath(board, "/columns/" + todo), MEMBER,
                Map.of("boardId", board, "name", "To do", "position", 9)).status());

        // A column created without a position goes to the end.
        long review = call("POST", w.boardPath(board, "/columns"), OWNER, Map.of("boardId", board, "name", "Review"))
                .expect(201).json().get("columnId").asLong();
        assertEquals(List.of("To do", "Doing", "Review"), w.snapshot(OWNER, board).get("columns").findValuesAsText("name"));
        call("DELETE", w.boardPath(board, "/columns/" + review), OWNER, null).expect(204);

        JsonNode column = call("POST", w.boardPath(board, "/columns/" + doing + "/move"), MOD, Map.of("index", 0))
                .expect(200).json();
        assertEquals(1, column.get("position").asInt());
        assertEquals(List.of("Doing", "To do"), w.snapshot(MOD, board).get("columns").findValuesAsText("name"));

        // Members without MOVE_TASK cannot move; moving to another board's column is refused.
        w.createRule(OWNER, "BOARD", board, "USER", MEMBER, "MOVE_TASK", "DENY").expect(201);
        assertEquals(403, w.moveTask(MEMBER, board, b, doing, 0).status());
        long otherBoard = w.createBoard(OWNER, "Elsewhere");
        assertEquals(400, w.moveTask(MOD, board, b, w.column(otherBoard, "Todo"), 0).status());

        // One drag is one audit entry, recording where the task came from.
        List<JsonNode> moves = auditLog(w).stream().filter(e -> "TASK_MOVED".equals(e.get("action").asText()))
                .toList();
        assertEquals(2, moves.size());
        assertEquals(todo, moves.get(1).at("/changes/columnId/from").asLong());
        assertEquals(doing, moves.get(1).at("/changes/columnId/to").asLong());
    }

    @Test
    void boardSnapshot_returnsEverythingTheBoardPageNeeds_andRespectsVisibility() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Snapshot");
        long todo = w.column(board, "Todo");
        long task = w.createTask(MOD, board, todo, "Visible");
        call("POST", w.boardPath(board, "/tasks/" + task + "/assignments"), MOD,
                Map.of("taskId", task, "userId", MEMBER)).expect(201);

        JsonNode snapshot = w.snapshot(MEMBER, board);
        assertEquals("Snapshot", snapshot.at("/board/name").asText());
        assertEquals(List.of("Todo", "Doing"), snapshot.get("columns").findValuesAsText("name"));
        assertEquals(List.of("Visible"), snapshot.get("tasks").findValuesAsText("title"));
        assertEquals(MEMBER, snapshot.at("/assignments/0/userId").asLong());
        assertTrue(snapshot.at("/permissions/VIEW_BOARD/allowed").asBoolean());
        assertTrue(snapshot.at("/permissions/CREATE_TASK/allowed").asBoolean());
        assertFalse(snapshot.at("/permissions/DELETE_BOARD/allowed").asBoolean());

        // A board the caller cannot view has no snapshot.
        w.createRule(OWNER, "BOARD", board, "USER", MEMBER, "VIEW_BOARD", "DENY").expect(201);
        assertEquals(403, call("GET", w.boardPath(board, "/snapshot"), MEMBER, null).status());
    }

    @Test
    void accessSummary_listsServerAndPerBoardPermissions_forVisibleBoardsOnly() throws Exception {
        World w = bootstrapServer();
        long open = w.createBoard(OWNER, "Open");
        long secret = w.createBoard(OWNER, "Secret");
        w.createRule(OWNER, "BOARD", secret, "DISCORD_PERMISSION", VIEW_CHANNEL, "VIEW_BOARD", "DENY").expect(201);
        w.createRule(OWNER, "BOARD", secret, "ROLE", w.modsRole, "VIEW_BOARD", "ALLOW").expect(201);
        w.createRule(OWNER, "BOARD", open, "USER", MEMBER, "CREATE_TASK", "DENY").expect(201);

        JsonNode owner = call("GET", w.path("/permissions/mine"), OWNER, null).expect(200).json();
        assertTrue(owner.at("/server/ADMIN").asBoolean());
        assertTrue(owner.at("/boards/" + secret + "/DELETE_BOARD").asBoolean());

        JsonNode member = call("GET", w.path("/permissions/mine"), MEMBER, null).expect(200).json();
        assertTrue(member.at("/server/VIEW_SERVER").asBoolean());
        assertFalse(member.at("/server/MANAGE_SERVER_PERMISSIONS").asBoolean());
        assertFalse(member.get("boards").has(String.valueOf(secret)), "boards the caller cannot view are left out");
        assertTrue(member.at("/boards/" + open + "/VIEW_TASK").asBoolean());
        assertFalse(member.at("/boards/" + open + "/CREATE_TASK").asBoolean(), "board overrides apply");

        assertTrue(call("GET", w.path("/permissions/mine"), MOD, null).expect(200).json()
                .get("boards").has(String.valueOf(secret)));
        long outsider = 9_999L;
        assertEquals(403, call("GET", w.path("/permissions/mine"), outsider, null).status());
    }

    // ── Audit log ───────────────────────────────────────────────────────────────

    @Test
    void everyChange_isRecordedInTheAuditLog_andSurvivesBoardDeletion() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Audited");
        long todo = w.column(board, "Todo");
        long task = w.createTask(MOD, board, todo, "Draft");
        w.updateTask(MOD, board, task, "Final", todo).expect(200);
        call("DELETE", w.boardPath(board, "/tasks/" + task), MOD, null).expect(204);
        // A rejected change leaves no trace.
        assertEquals(403, w.createRule(MEMBER, "SERVER", w.serverId, "USER", MEMBER, "VIEW_AUDIT_LOG", "ALLOW")
                .status());

        // Leave out the features switched on while setting up the test server.
        List<JsonNode> log = auditLog(w).stream()
                .filter(e -> !"SERVER_FEATURES_UPDATED".equals(e.get("action").asText()))
                .toList();
        assertEquals(List.of("BOARD_CREATED", "TASK_CREATED", "TASK_UPDATED", "TASK_DELETED"),
                log.stream().map(e -> e.get("action").asText()).toList());

        JsonNode created = log.get(1);
        assertEquals(MOD, created.get("userId").asLong());
        assertEquals(board, created.get("boardId").asLong());
        assertEquals(task, created.get("entityId").asLong());
        assertEquals("Draft", created.at("/changes/created/title").asText());

        JsonNode updated = log.get(2);
        assertEquals("Draft", updated.at("/changes/title/from").asText());
        assertEquals("Final", updated.at("/changes/title/to").asText());
        assertFalse(updated.get("changes").has("updatedAt"), "only fields the user changed are recorded");

        assertEquals("Final", log.get(3).at("/changes/deleted/title").asText());

        // Deleting the board keeps its history (detached from the board) and records the deletion.
        call("DELETE", w.boardPath(board, ""), OWNER, null).expect(204);
        List<JsonNode> afterDelete = auditLog(w).stream()
                .filter(e -> !"SERVER_FEATURES_UPDATED".equals(e.get("action").asText()))
                .toList();
        assertEquals(5, afterDelete.size());
        JsonNode deleted = afterDelete.get(4);
        assertEquals("BOARD_DELETED", deleted.get("action").asText());
        assertEquals(board, deleted.at("/changes/deleted/boardId").asLong());
        assertTrue(afterDelete.stream().allMatch(e -> e.get("boardId").isNull()), afterDelete.toString());
    }

    /** The server's audit log, oldest first. */
    @Test
    void auditLog_pagesNewestFirst_filtersCombine_andEntriesCarryNames() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Audited");
        long todo = w.column(board, "Todo");
        long one = w.createTask(MOD, board, todo, "One");
        w.createTask(MEMBER, board, todo, "Two");
        w.createTask(MOD, board, todo, "Three");

        JsonNode first = call("GET", w.path("/audit-logs?limit=2"), OWNER, null).expect(200).json();
        assertEquals(2, first.get("entries").size());
        assertEquals("TASK_CREATED", first.get("entries").get(0).get("action").asText(), "newest first");
        long nextBefore = first.get("nextBefore").asLong();
        JsonNode second = call("GET", w.path("/audit-logs?limit=2&before=" + nextBefore), OWNER, null).expect(200).json();
        assertTrue(second.get("entries").get(0).get("logId").asLong() < nextBefore, "the next page continues older");

        JsonNode modTasks = call("GET", w.path("/audit-logs?entityType=TASK&actorUserId=" + MOD + "&boardId=" + board),
                OWNER, null).expect(200).json();
        assertEquals(2, modTasks.get("entries").size(), modTasks.toString());
        JsonNode entry = modTasks.get("entries").get(0);
        assertEquals("Audited", entry.get("boardName").asText());
        assertEquals(String.valueOf(MOD), entry.get("userId").asText());
        assertFalse(entry.get("actorDisplayName").asText().isBlank());
        assertTrue(modTasks.get("nextBefore").isNull());

        JsonNode boardsAndTasks = call("GET", w.path("/audit-logs?entityType=BOARD&entityType=TASK"), OWNER, null)
                .expect(200).json();
        assertEquals(4, boardsAndTasks.get("entries").size(), "the board and its three tasks");
        assertEquals(400, call("GET", w.path("/audit-logs?limit=500"), OWNER, null).status());

        // An edit that leaves the title alone still names the task.
        call("PUT", w.boardPath(board, "/tasks/" + one), MOD,
                Map.of("title", "One", "description", "more detail", "boardId", board, "columnId", todo)).expect(200);
        JsonNode edit = call("GET", w.path("/audit-logs?limit=1"), OWNER, null).expect(200).json().get("entries").get(0);
        assertEquals("TASK_UPDATED", edit.get("action").asText());
        assertEquals("One", edit.get("changes").get("_subject").asText());
        assertTrue(edit.get("changes").has("description"));

        // Moves name the columns, as they were called at the time.
        long doing = w.column(board, "Doing");
        w.moveTask(MOD, board, one, doing, 0).expect(200);
        JsonNode moved = call("GET", w.path("/audit-logs?limit=1"), OWNER, null).expect(200).json().get("entries").get(0);
        assertEquals("TASK_MOVED", moved.get("action").asText());
        assertEquals("Todo", moved.get("changes").get("_fromColumn").asText());
        assertEquals("Doing", moved.get("changes").get("_column").asText());
        long two = w.createTask(MOD, board, doing, "Four");
        w.moveTask(MOD, board, two, doing, 0).expect(200);
        JsonNode reordered = call("GET", w.path("/audit-logs?limit=1"), OWNER, null).expect(200).json().get("entries").get(0);
        assertEquals("Doing", reordered.get("changes").get("_column").asText());
        assertFalse(reordered.get("changes").has("_fromColumn"), "a reorder stays in its column");

        // Only people who changed something are offered as filters.
        List<String> actors = new ArrayList<>();
        call("GET", w.path("/audit-logs/actors"), OWNER, null).expect(200).json()
                .forEach(actor -> actors.add(actor.get("userId").asText()));
        assertEquals(3, actors.size(), actors.toString());
        assertTrue(actors.containsAll(List.of(String.valueOf(OWNER), String.valueOf(MOD), String.valueOf(MEMBER))));
        assertFalse(actors.contains(String.valueOf(OTHER)));
        assertEquals(403, call("GET", w.path("/audit-logs/actors"), MEMBER, null).status());
    }

    private List<JsonNode> auditLog(World w) throws Exception {
        List<JsonNode> entries = new ArrayList<>();
        call("GET", w.path("/audit-logs?limit=100"), OWNER, null).expect(200).json().get("entries").forEach(entries::add);
        entries.sort(java.util.Comparator.comparing(e -> e.get("logId").asLong()));
        return entries;
    }

    // ── Discord sync ─────────────────────────────────────────────────────────

    @Test
    void everyoneRole_appliesToEveryMember_andDiscordSyncTakesEffectImmediately() throws Exception {
        World w = bootstrapServer(true);
        long board = w.createBoard(OWNER, "Everyone");
        long todo = w.column(board, "Todo");

        // NEWBIE has no roles; View Channels comes from @everyone, as in a default Discord server.
        assertTrue(w.visibleBoards(NEWBIE).contains(board));
        RealtimeProbe newbie = RealtimeProbe.connect(this, NEWBIE);
        newbie.subscribe(w.topic(board));
        RealtimeProbe member = RealtimeProbe.connect(this, MEMBER);
        member.subscribe(w.topic(board));
        Thread.sleep(500);
        w.createTask(MOD, board, todo, "Before");
        assertEquals("TASK_CREATED", newbie.next().get("eventType"));
        member.drain();

        // @everyone loses View Channels in Discord and the bot syncs the role.
        assertEquals(204, sync("PUT", "/api/internal/sync/servers/" + w.serverId + "/roles/" + w.serverId,
                Map.of("name", "@everyone", "position", 0, "discordPermissions", 0L)));
        // Without View Channels a roleless member no longer sees the server at all.
        assertEquals(403, call("GET", w.boardPath(board, "/tasks"), NEWBIE, null).status());
        w.createTask(MOD, board, todo, "After");
        assertEquals("TASK_CREATED", member.next().get("eventType"));
        List<Map<?, ?>> newbieEvents = newbie.drain();
        assertTrue(newbieEvents.isEmpty(), "realtime access is re-evaluated on sync: " + newbieEvents);
    }

    @Test
    void ownershipTransfer_isSynced() throws Exception {
        World w = bootstrapServer();
        assertEquals(403, call("GET", w.path("/audit-logs"), MEMBER, null).status());

        assertEquals(204, sync("PUT", "/api/internal/sync/servers/" + w.serverId,
                Map.of("name", "E2E " + w.serverId, "ownerId", MEMBER, "ownerUsername", "member")));

        call("GET", w.path("/audit-logs"), MEMBER, null).expect(200);
        assertEquals(403, call("GET", w.path("/audit-logs"), OWNER, null).status());
    }

    // ── Migration V9 ───────────────────────────────────────────────────────────

    @Test
    void v9Migration_removesUnchangedBoardCopies_andKeepsOverrides() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Legacy");
        long viewBoard = w.catalog.get("VIEW_BOARD");

        // The server's default rule (VIEW_CHANNEL → VIEW_BOARD ALLOW) exists from the bootstrap.
        insertBoardRule(board, "DISCORD_PERMISSION", VIEW_CHANNEL, viewBoard, "ALLOW"); // legacy copy
        insertBoardRule(board, "DISCORD_PERMISSION", VIEW_CHANNEL, viewBoard, "DENY"); // real override
        insertBoardRule(board, "ROLE", w.modsRole, viewBoard, "ALLOW"); // board-only rule

        String v9 = new ClassPathResource("db/migration/V9__boards_inherit_server_permissions.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbcTemplate.execute(v9);

        List<String> remaining = jdbcTemplate.queryForList(
                "SELECT subject_type || ':' || state FROM permissions WHERE scope_type = 'BOARD' AND scope_id = ? "
                        + "ORDER BY subject_type, state", String.class, board);
        assertEquals(List.of("DISCORD_PERMISSION:DENY", "ROLE:ALLOW"), remaining);
    }

    // ── Role assignments ────────────────────────────────────────────────────────

    @Test
    void roleAssignments_needAssignOthers_mustBeServerRoles_andAppearInTheSnapshot() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Roles");
        long task = w.createTask(MOD, board, w.column(board, "Todo"), "For the mods");
        String path = w.boardPath(board, "/tasks/" + task + "/role-assignments");

        RealtimeProbe member = RealtimeProbe.connect(this, MEMBER);
        member.subscribe(w.topic(board));
        Thread.sleep(500);

        assertEquals(403, call("POST", path, MEMBER, Map.of("roleId", w.modsRole)).status(),
                "assigning a role is assigning others");
        long assignment = call("POST", path, MOD, Map.of("roleId", w.modsRole)).expect(201).json().get("id").asLong();
        assertEquals("TASK_ROLE_ASSIGNED", member.next().get("eventType"));
        assertEquals(400, call("POST", path, MOD, Map.of("roleId", w.modsRole)).status(), "at most once per task");
        assertEquals(400, call("POST", path, MOD, Map.of("roleId", 123L)).status(), "only this server's roles");

        JsonNode listed = w.snapshot(MEMBER, board).get("roleAssignments");
        assertEquals(1, listed.size());
        assertEquals(String.valueOf(w.modsRole), listed.get(0).get("roleId").asText());
        assertTrue(auditLog(w).stream().anyMatch(e -> "TASK_ROLE_ASSIGNED".equals(e.get("action").asText())));

        // Deleting the role in Discord removes it from tasks.
        assertEquals(204, sync("DELETE", "/api/internal/sync/servers/" + w.serverId + "/roles/" + w.membersRole, Map.of()));
        call("POST", path, MOD, Map.of("roleId", w.membersRole)).expect(400);

        call("DELETE", path + "/" + assignment, MOD, null).expect(204);
        // Without the Members role the member no longer sees the board; the mod still does.
        assertEquals(0, w.snapshot(MOD, board).get("roleAssignments").size());
    }

    // ── Simple mode ─────────────────────────────────────────────────────────────

    @Test
    void simpleMode_newServersStartWithTheCoreOnly_andSwitchedOffFeaturesKeepTheirData() throws Exception {
        World w = bootstrapServer();
        call("PUT", w.path("/features"), OWNER, Map.of("LABELS", false, "PRIORITIES", false, "ASSIGNEES", false,
                "COMMENTS", false, "DUE_DATES", false, "PERMISSIONS", false)).expect(200);
        long board = w.createBoard(OWNER, "Plain");
        long todo = w.column(board, "Todo");

        // A brand-new server, straight from the bot, is in simple mode.
        World fresh = bootstrapServerWithoutFeatures();
        JsonNode freshFeatures = call("GET", fresh.path("/features"), MEMBER, null).expect(200).json();
        freshFeatures.fields().forEachRemaining(entry -> assertFalse(entry.getValue().asBoolean(), entry.getKey()));

        // The core works: tasks with a title and description.
        long task = w.createTask(MOD, board, todo, "Just a task");

        // Everything optional is refused with 409 and a readable reason.
        Response label = call("POST", w.boardPath(board, "/labels"), OWNER, Map.of("boardId", board, "name", "x", "color", "#ff0000"));
        assertEquals(409, label.status());
        assertTrue(label.body().contains("Labels are turned off"), label.body());
        assertEquals(409, call("POST", w.boardPath(board, "/priorities"), OWNER, Map.of("name", "Urgent")).status());
        assertEquals(409, call("POST", w.boardPath(board, "/tasks/" + task + "/assignments"), MOD,
                Map.of("taskId", task, "userId", MOD)).status());
        assertEquals(409, call("POST", w.boardPath(board, "/tasks/" + task + "/comments"), MOD,
                Map.of("taskId", task, "content", "hi")).status());
        assertEquals(409, call("GET", w.boardPath(board, "/tasks/" + task + "/comments"), MOD, null).status());
        assertEquals(409, w.createRule(OWNER, "BOARD", board, "USER", MEMBER, "VIEW_BOARD", "DENY").status());

        JsonNode snapshot = w.snapshot(MOD, board);
        assertFalse(snapshot.get("features").get("LABELS").asBoolean());
        assertEquals(0, snapshot.get("priorities").size(), "switched-off lists are left out");

        // Switch priorities and due dates on, set them, switch them off: saving the task keeps them.
        call("PUT", w.path("/features"), OWNER, Map.of("PRIORITIES", true, "DUE_DATES", true)).expect(200);
        long high = w.snapshot(MOD, board).get("priorities").get(1).get("priorityId").asLong();
        Map<String, Object> edit = new HashMap<>(Map.of("title", "Just a task", "boardId", board, "columnId", todo,
                "dueDate", "2030-01-01T09:00:00"));
        edit.put("priorityId", high);
        call("PUT", w.boardPath(board, "/tasks/" + task), MOD, edit).expect(200);
        call("PUT", w.path("/features"), OWNER, Map.of("PRIORITIES", false, "DUE_DATES", false)).expect(200);

        Map<String, Object> hiddenEdit = new HashMap<>(Map.of("title", "Renamed", "boardId", board, "columnId", todo));
        hiddenEdit.put("priorityId", null);
        JsonNode saved = call("PUT", w.boardPath(board, "/tasks/" + task), MOD, hiddenEdit).expect(200).json();
        assertEquals(high, saved.get("priorityId").asLong(), "a hidden priority survives an edit");
        assertEquals("2030-01-01T09:00:00", saved.get("dueDate").asText(), "a hidden due date survives an edit");

        // With due dates on, an edit without one clears it.
        call("PUT", w.path("/features"), OWNER, Map.of("DUE_DATES", true)).expect(200);
        JsonNode cleared = call("PUT", w.boardPath(board, "/tasks/" + task), MOD, hiddenEdit).expect(200).json();
        assertTrue(cleared.get("dueDate").isNull(), "a due date can be removed");
        call("PUT", w.path("/features"), OWNER, Map.of("DUE_DATES", false)).expect(200);

        // Only server administrators change features, and the change is audited.
        assertEquals(403, call("PUT", w.path("/features"), MOD, Map.of("LABELS", true)).status());
        assertEquals(400, call("PUT", w.path("/features"), OWNER, Map.of("NOPE", true)).status());
        assertTrue(auditLog(w).stream().anyMatch(e -> "SERVER_FEATURES_UPDATED".equals(e.get("action").asText())));
    }

    @Test
    void boardSimpleMode_narrowsTheServersFeatures_forThatBoardOnly() throws Exception {
        World w = bootstrapServer();
        long plain = w.createBoard(OWNER, "Plain");
        long full = w.createBoard(OWNER, "Full");
        long task = w.createTask(MOD, plain, w.column(plain, "Todo"), "Just a task");
        String features = w.boardPath(plain, "/features");

        RealtimeProbe member = RealtimeProbe.connect(this, MEMBER);
        member.subscribe(w.topic(plain));
        Thread.sleep(500);

        // Only people who edit the board's details switch its features; permissions stay server-wide.
        assertEquals(403, call("PUT", features, MEMBER, Map.of("LABELS", false)).status());
        assertEquals(400, call("PUT", features, OWNER, Map.of("PERMISSIONS", false)).status());
        JsonNode switches = call("PUT", features, OWNER, Map.of("LABELS", false, "COMMENTS", false)).expect(200).json();
        assertFalse(switches.get("LABELS").asBoolean());
        assertTrue(switches.get("PRIORITIES").asBoolean());
        assertFalse(switches.has("PERMISSIONS"));
        assertEquals("BOARD_FEATURES_UPDATED", lastEventType(member));

        // The board refuses what it switched off; the other board keeps everything.
        assertEquals(409, call("POST", w.boardPath(plain, "/labels"), OWNER,
                Map.of("boardId", plain, "name", "x", "color", "#ff0000")).status());
        assertEquals(409, call("POST", w.boardPath(plain, "/tasks/" + task + "/comments"), MOD,
                Map.of("taskId", task, "content", "hi")).status());
        call("POST", w.boardPath(full, "/labels"), OWNER, Map.of("boardId", full, "name", "x", "color", "#ff0000"))
                .expect(201);

        JsonNode snapshot = w.snapshot(MEMBER, plain);
        assertFalse(snapshot.get("features").get("LABELS").asBoolean());
        assertTrue(snapshot.get("serverFeatures").get("LABELS").asBoolean());
        assertTrue(w.snapshot(MEMBER, full).get("features").get("LABELS").asBoolean());

        // Switching on for the board cannot switch on what the server has off.
        call("PUT", w.path("/features"), OWNER, Map.of("PRIORITIES", false)).expect(200);
        call("PUT", features, OWNER, Map.of("PRIORITIES", true)).expect(200);
        assertFalse(w.snapshot(MEMBER, plain).get("features").get("PRIORITIES").asBoolean());

        // Switching back on restores the board, and every change is in the audit log.
        call("PUT", features, OWNER, Map.of("LABELS", true)).expect(200);
        assertTrue(w.snapshot(MEMBER, plain).get("features").get("LABELS").asBoolean());
        assertEquals(2, auditLog(w).stream().filter(e -> "BOARD_FEATURES_UPDATED".equals(e.get("action").asText())).count());
    }

    // ── Full server sync ────────────────────────────────────────────────────────

    @Test
    void serverSync_makesMembersAndRolesExactlyWhatDiscordHas_andIsFastForLargeServers() throws Exception {
        World w = bootstrapServer();
        String bootstrap = "/api/internal/sync/servers/" + w.serverId + "/bootstrap";
        long gone = w.serverId + 3;

        // Discord now: OTHER left, the Mods role was deleted, MEMBER changed avatar and became a mod
        // of a new role, and 5000 more people joined.
        List<Map<String, Object>> members = new ArrayList<>(List.of(
                Map.of("userId", OWNER, "username", "owner", "roleIds", List.of()),
                Map.of("userId", MOD, "username", "mod", "roleIds", List.of(w.membersRole)),
                Map.of("userId", MEMBER, "username", "member", "avatarUrl", "https://cdn.example/new.png",
                        "roleIds", List.of(w.membersRole, gone, 424242L))));
        for (long i = 0; i < 5000; i++) {
            members.add(Map.of("userId", 5_000_000L + i, "username", "crowd" + i, "roleIds", List.of(w.membersRole)));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Renamed");
        body.put("ownerId", OWNER);
        body.put("ownerUsername", "owner");
        body.put("roles", List.of(
                Map.of("roleId", w.membersRole, "name", "Members", "position", 1, "discordPermissions", VIEW_CHANNEL | SEND_MESSAGES),
                Map.of("roleId", gone, "name", "New role", "position", 3, "discordPermissions", VIEW_CHANNEL)));
        body.put("members", members);

        long started = System.nanoTime();
        assertEquals(204, sync("POST", bootstrap, body));
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(millis < 10_000, "5000 members synced in " + millis + " ms");

        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM server_members WHERE server_id = ?", Long.class, w.serverId);
        assertEquals(5003L, count);
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM server_members WHERE server_id = ? AND user_id = ?", Integer.class, w.serverId, OTHER),
                "people who left are removed");
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM roles WHERE role_id = ?", Integer.class, w.modsRole),
                "deleted roles are removed");
        assertEquals("https://cdn.example/new.png", jdbcTemplate.queryForObject(
                "SELECT avatar_url FROM users WHERE user_id = ?", String.class, MEMBER));
        assertEquals(List.of(w.membersRole, gone), jdbcTemplate.queryForList("""
                SELECT mr.role_id FROM member_roles mr JOIN server_members sm ON sm.id = mr.server_member_id
                WHERE sm.server_id = ? AND sm.user_id = ? ORDER BY mr.role_id
                """, Long.class, w.serverId, MEMBER), "roles are replaced; ids the server does not have are skipped");
        assertEquals("Renamed", jdbcTemplate.queryForObject("SELECT name FROM servers WHERE server_id = ?", String.class, w.serverId));

        // MOD lost the Mods role, so they are an ordinary member now.
        long board = w.createBoard(OWNER, "After sync");
        long task = w.createTask(MOD, board, w.column(board, "Todo"), "Still a member");
        assertEquals(403, w.updateTask(MOD, board, task, "Renamed", w.column(board, "Todo")).status());

        // A sync that could not read the members (an empty list) removes nobody, and no one's roles.
        body.put("members", List.of());
        assertEquals(204, sync("POST", bootstrap, body));
        assertEquals(5003L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM server_members WHERE server_id = ?", Long.class, w.serverId));
        assertEquals(5002L, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM member_roles mr JOIN server_members sm ON sm.id = mr.server_member_id
                WHERE sm.server_id = ? AND mr.role_id = ?
                """, Long.class, w.serverId, w.membersRole));
    }

    // ── The bot acting for a user ───────────────────────────────────────────────

    @Test
    void botActingUser_runsAsThatUser_withTheirPermissions_onlyInTheirServer() throws Exception {
        World w = bootstrapServer();
        World other = bootstrapServer();
        long board = w.createBoard(OWNER, "From Discord");
        long todo = w.column(board, "Todo");
        Map<String, Object> task = Map.of("title", "Made in Discord", "boardId", board, "columnId", todo);

        // Runs as the user: a member can create tasks, and the audit log says it came from Discord.
        long taskId = asBot("POST", w.boardPath(board, "/tasks"), BOT_TOKEN, MEMBER, w.serverId, task)
                .expect(201).json().get("taskId").asLong();
        JsonNode entry = auditLog(w).stream()
                .filter(e -> "TASK_CREATED".equals(e.get("action").asText())).findFirst().orElseThrow();
        assertEquals("DISCORD", entry.get("source").asText());
        assertEquals(String.valueOf(MEMBER), entry.get("userId").asText());

        // With that user's permissions: members cannot rename tasks or create boards.
        assertEquals(403, asBot("PUT", w.boardPath(board, "/tasks/" + taskId), BOT_TOKEN, MEMBER, w.serverId,
                Map.of("title", "Renamed", "boardId", board, "columnId", todo)).status());
        assertEquals(403, asBot("POST", w.path("/boards"), BOT_TOKEN, MEMBER, w.serverId,
                Map.of("name", "Nope", "serverId", w.serverId)).status());
        assertEquals(403, asBot("GET", w.path("/boards"), BOT_TOKEN, 9_998L, w.serverId, null).status(),
                "someone outside the server gets nothing");

        // Only with the bot's token, only in the server the command ran in, never with a user token too.
        assertEquals(401, asBot("GET", w.path("/boards"), "wrong-token", MEMBER, w.serverId, null).status());
        assertEquals(403, asBot("GET", other.path("/boards"), BOT_TOKEN, OWNER, w.serverId, null).status(),
                "a command in one server cannot reach another, even where the user is a member");
        assertEquals(403, asBot("GET", "/api/me", BOT_TOKEN, OWNER, w.serverId, null).status());
        assertEquals(403, asBot("GET", "/api/audit-logs", BOT_TOKEN, OWNER, w.serverId, null).status());
        assertEquals(400, asBot("GET", w.path("/boards"), BOT_TOKEN, "abc", w.serverId, null).status());
        HttpResponse<String> both = http.send(HttpRequest.newBuilder(uri(w.path("/boards")))
                .header("Authorization", "Bearer " + accessToken(OWNER))
                .header("X-Internal-Bot-Token", BOT_TOKEN)
                .header("X-Acting-User-Id", String.valueOf(OWNER))
                .header("X-Acting-Guild-Id", String.valueOf(w.serverId))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, both.statusCode());
    }

    private Response asBot(String method, String path, String botToken, Object userId, long guildId, Object body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Internal-Bot-Token", botToken)
                .header("X-Acting-User-Id", String.valueOf(userId))
                .header("X-Acting-Guild-Id", String.valueOf(guildId))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = response.body().startsWith("{") ? objectMapper.readTree(response.body()) : null;
        return new Response(response.statusCode(), json, response.body());
    }

    // ── Media uploads ───────────────────────────────────────────────────────────

    @Test
    void mediaUploads_passFilesOnToImgur_andReturnOnlyTheLink() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Media");
        String media = w.boardPath(board, "/media");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};

        JsonNode image = upload(media, MOD, "holiday photo.png", png).expect(201).json();
        assertEquals("https://i.imgur.com/fake1.png", image.get("url").asText());
        assertEquals("IMAGE", image.get("kind").asText());
        FakeImgur.Request sent = FAKE_IMGUR.last();
        assertEquals("Client-ID test-client-id", sent.authorization());
        assertEquals(String.valueOf(sent.body().length()), sent.contentLength(), "sent with its exact length, not chunked");
        assertTrue(sent.body().contains("name=\"type\"\r\n\r\nfile"), sent.body());
        assertTrue(sent.body().contains("name=\"image\"; filename=\"upload.png\""),
                "sent as an image, without the user's own file name: " + sent.body());
        assertEquals("fakehash1", jdbcTemplate.queryForObject(
                "SELECT delete_hash FROM media_uploads WHERE board_id = ?", String.class, board),
                "the delete hash is kept so the file can be taken down later");

        byte[] mp4 = "\0\0\0 ftypisom\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("VIDEO", upload(media, MOD, "clip.mp4", mp4).expect(201).json().get("kind").asText());
        assertTrue(FAKE_IMGUR.last().body().contains("name=\"video\""));

        // Anyone who can create tasks may upload; outsiders may not, and nothing reaches Imgur.
        upload(media, MEMBER, "a.png", png).expect(201);
        int sentSoFar = FAKE_IMGUR.count();
        assertEquals(403, upload(media, 9_999L, "a.png", png).status());

        // What the file is decides, not what it is called.
        Response svg = upload(media, MOD, "cat.png", "<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8));
        assertEquals(400, svg.status());
        assertTrue(svg.body().contains("Only PNG"), svg.body());
        assertEquals(sentSoFar, FAKE_IMGUR.count());

        // When Imgur is limiting us, the reason is readable and nothing is recorded.
        FAKE_IMGUR.respondWith(429);
        try {
            Response busy = upload(media, MOD, "a.png", png);
            assertEquals(503, busy.status());
            assertTrue(busy.body().contains("limiting uploads"), busy.body());
        } finally {
            FAKE_IMGUR.respondWith(200);
        }
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_uploads WHERE board_id = ?", Integer.class, board));
    }

    private Response upload(String path, long userId, String filename, byte[] content) throws Exception {
        String boundary = "kc" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + accessToken(userId))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = response.body().startsWith("{") ? objectMapper.readTree(response.body()) : null;
        return new Response(response.statusCode(), json, response.body());
    }

    /** A local stand-in for Imgur's upload endpoint that records what it was sent. */
    static final class FakeImgur {

        record Request(String authorization, String contentLength, String body) {
        }

        private final HttpServer server;
        private final List<Request> requests = new ArrayList<>();
        private volatile int status = 200;

        FakeImgur() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            server.createContext("/3/upload", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
                int n;
                synchronized (requests) {
                    requests.add(new Request(exchange.getRequestHeaders().getFirst("Authorization"),
                            exchange.getRequestHeaders().getFirst("Content-Length"), body));
                    n = requests.size();
                }
                String extension = body.contains("name=\"video\"") ? "mp4" : "png";
                byte[] response = (status == 200
                        ? "{\"data\":{\"id\":\"fake" + n + "\",\"deletehash\":\"fakehash" + n
                                + "\",\"link\":\"https://i.imgur.com/fake" + n + "." + extension + "\"},\"success\":true}"
                        : "{\"data\":{\"error\":\"limited\"},\"success\":false}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void respondWith(int status) {
            this.status = status;
        }

        int count() {
            synchronized (requests) {
                return requests.size();
            }
        }

        Request last() {
            synchronized (requests) {
                return requests.get(requests.size() - 1);
            }
        }
    }

    // ── Priority levels ─────────────────────────────────────────────────────────

    @Test
    void priorities_newBoardsGetDefaults_levelsAreManagedAndOrdered_andTasksPointAtOne() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Prioritised");
        long todo = w.column(board, "Todo");
        String priorities = w.boardPath(board, "/priorities");

        JsonNode defaults = w.snapshot(MEMBER, board).get("priorities");
        assertEquals(List.of("Critical", "High", "Medium", "Low", "Ignorable"), names(defaults));
        long high = defaults.get(1).get("priorityId").asLong();
        long ignorable = defaults.get(4).get("priorityId").asLong();

        RealtimeProbe member = RealtimeProbe.connect(this, MEMBER);
        member.subscribe(w.topic(board));
        Thread.sleep(500);

        // Setting a priority is editing the task.
        long task = w.createTask(MOD, board, todo, "Urgent");
        member.drain();
        Map<String, Object> edit = new HashMap<>(Map.of("title", "Urgent", "boardId", board, "columnId", todo));
        edit.put("priorityId", high);
        assertEquals(403, call("PUT", w.boardPath(board, "/tasks/" + task), MEMBER, edit).status());
        assertEquals(high, call("PUT", w.boardPath(board, "/tasks/" + task), MOD, edit).expect(200)
                .json().get("priorityId").asLong());

        // Managing levels needs MANAGE_PRIORITIES; new ones go to the bottom.
        assertEquals(403, call("POST", priorities, MEMBER, Map.of("name", "Blocker")).status());
        long blocker = call("POST", priorities, OWNER, Map.of("name", "Blocker", "color", "#7c3aed"))
                .expect(201).json().get("priorityId").asLong();
        assertEquals("PRIORITY_CREATED", lastEventType(member));
        assertEquals(400, call("POST", priorities, OWNER, Map.of("name", "blocker")).status(),
                "names are unique per board, ignoring case");
        assertEquals(400, call("POST", priorities, OWNER, Map.of("name", "Odd", "color", "red")).status());

        call("POST", priorities + "/" + blocker + "/move", OWNER, Map.of("index", 0)).expect(200);
        assertEquals("PRIORITY_MOVED", lastEventType(member));
        call("PUT", priorities + "/" + ignorable, OWNER, Map.of("name", "Someday")).expect(200);
        assertEquals(List.of("Blocker", "Critical", "High", "Medium", "Low", "Someday"),
                names(w.snapshot(OWNER, board).get("priorities")));

        // Deleting a level in use leaves its tasks without a priority; positions close the gap.
        call("DELETE", priorities + "/" + high, OWNER, null).expect(204);
        assertEquals("PRIORITY_DELETED", lastEventType(member));
        JsonNode snapshot = w.snapshot(OWNER, board);
        assertTrue(snapshot.get("tasks").get(0).get("priorityId").isNull());
        List<Integer> positions = new ArrayList<>();
        snapshot.get("priorities").forEach(level -> positions.add(level.get("position").asInt()));
        assertEquals(List.of(1, 2, 3, 4, 5), positions);

        // A task can only use its own board's levels.
        long other = w.createBoard(OWNER, "Other");
        long foreign = w.snapshot(OWNER, other).get("priorities").get(0).get("priorityId").asLong();
        edit.put("priorityId", foreign);
        assertEquals(400, call("PUT", w.boardPath(board, "/tasks/" + task), MOD, edit).status());
    }

    @Test
    void v12Migration_turnsFreeTextPrioritiesIntoBoardLevels_andGrantsManagePriorities() throws Exception {
        String schema = "v12_check";
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).target("11").load().migrate();

        String s = schema + ".";
        jdbcTemplate.update("INSERT INTO " + s + "users (user_id, username) VALUES (1, 'u')");
        jdbcTemplate.update("INSERT INTO " + s + "servers (server_id, name, owner_id) VALUES (1, 's', 1)");
        jdbcTemplate.update("INSERT INTO " + s + "boards (board_id, server_id, name, created_by) VALUES (1, 1, 'b', 1)");
        jdbcTemplate.update("INSERT INTO " + s + "columns (column_id, board_id, name) VALUES (1, 1, 'c')");
        String[] legacy = {"HIGH", " high ", "Urgent", "low", null, ""};
        for (int i = 0; i < legacy.length; i++) {
            jdbcTemplate.update("INSERT INTO " + s + "tasks (board_id, column_id, title, priority, created_by) "
                    + "VALUES (1, 1, ?, ?, 1)", "t" + i, legacy[i]);
        }
        jdbcTemplate.update("INSERT INTO " + s + "permissions (scope_type, scope_id, subject_type, subject_id, "
                + "kanban_permission_id, state, priority) SELECT 'SERVER', 1, 'ROLE', 7, permission_id, 'ALLOW', 120 "
                + "FROM " + s + "kanban_permissions WHERE key = 'CREATE_LABEL'");

        org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).target("12").load().migrate();

        assertEquals(List.of("Critical", "High", "Medium", "Low", "Ignorable", "Urgent"), jdbcTemplate.queryForList(
                "SELECT name FROM " + s + "board_priorities WHERE board_id = 1 ORDER BY position", String.class));
        assertEquals(List.of("High", "High", "Urgent", "Low", "-", "-"), jdbcTemplate.queryForList(
                "SELECT coalesce(p.name, '-') FROM " + s + "tasks t LEFT JOIN " + s + "board_priorities p "
                        + "ON p.priority_id = t.priority_id ORDER BY t.task_id", String.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM " + s + "permissions r JOIN " + s
                + "kanban_permissions k ON k.permission_id = r.kanban_permission_id "
                + "WHERE k.key = 'MANAGE_PRIORITIES' AND r.subject_type = 'ROLE' AND r.subject_id = 7", Integer.class));
        jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    private static List<String> names(JsonNode levels) {
        List<String> names = new ArrayList<>();
        levels.forEach(level -> names.add(level.get("name").asText()));
        return names;
    }

    private static String lastEventType(RealtimeProbe probe) throws InterruptedException {
        List<Map<?, ?>> events = probe.drain();
        assertFalse(events.isEmpty(), "expected a realtime event");
        return String.valueOf(events.get(events.size() - 1).get("eventType"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private World bootstrapServer() throws Exception {
        return bootstrapServer(false);
    }

    /** A server as the bot creates it, before anyone has switched on optional features. */
    private World bootstrapServerWithoutFeatures() throws Exception {
        return bootstrapServer(false, false);
    }

    /**
     * @param withEveryone also sync an @everyone role (id = server id) granting View Channels, and
     *                     NEWBIE, a member with no roles
     */
    private World bootstrapServer(boolean withEveryone) throws Exception {
        return bootstrapServer(withEveryone, true);
    }

    private World bootstrapServer(boolean withEveryone, boolean allFeatures) throws Exception {
        long serverId = SERVER_IDS.addAndGet(1_000);
        World w = new World(serverId, serverId + 1, serverId + 2);

        List<Map<String, Object>> roles = new ArrayList<>(List.of(
                Map.of("roleId", w.membersRole, "name", "Members", "position", 1,
                        "discordPermissions", VIEW_CHANNEL | SEND_MESSAGES),
                Map.of("roleId", w.modsRole, "name", "Mods", "position", 2,
                        "discordPermissions", VIEW_CHANNEL | SEND_MESSAGES | MANAGE_MESSAGES)));
        List<Map<String, Object>> members = new ArrayList<>(List.of(
                Map.of("userId", OWNER, "username", "owner", "roleIds", List.of()),
                Map.of("userId", MOD, "username", "mod", "roleIds", List.of(w.membersRole, w.modsRole)),
                Map.of("userId", MEMBER, "username", "member", "roleIds", List.of(w.membersRole)),
                Map.of("userId", OTHER, "username", "other", "roleIds", List.of(w.membersRole))));
        if (withEveryone) {
            roles.add(Map.of("roleId", serverId, "name", "@everyone", "position", 0,
                    "discordPermissions", VIEW_CHANNEL));
            members.add(Map.of("userId", NEWBIE, "username", "newbie", "roleIds", List.of()));
        }

        Map<String, Object> body = new HashMap<>();
        body.put("name", "E2E " + serverId);
        body.put("ownerId", OWNER);
        body.put("ownerUsername", "owner");
        body.put("roles", roles);
        body.put("members", members);
        assertEquals(204, sync("POST", "/api/internal/sync/servers/" + serverId + "/bootstrap", body));

        for (JsonNode entry : call("GET", w.path("/permissions/catalog"), OWNER, null).expect(200).json()) {
            w.catalog.put(entry.get("key").asText(), entry.get("permissionId").asLong());
        }
        // New servers start in simple mode; most tests exercise every feature.
        if (allFeatures) {
            call("PUT", w.path("/features"), OWNER, ALL_FEATURES).expect(200);
        }
        return w;
    }

    /** Calls the internal sync API the way the bot does; returns the status. */
    private int sync(String method, String path, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Internal-Bot-Token", BOT_TOKEN)
                .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private void insertBoardRule(long boardId, String subjectType, long subjectId, long kanbanPermissionId,
            String state) {
        jdbcTemplate.update("INSERT INTO permissions (scope_type, scope_id, subject_type, subject_id, "
                + "kanban_permission_id, state, priority, is_immutable) VALUES ('BOARD', ?, ?, ?, ?, ?, 100, false)",
                boardId, subjectType, subjectId, kanbanPermissionId, state);
    }

    Response call(String method, String path, Long userId, Object body) throws Exception {
        return callRaw(method, path, userId == null ? null : "Bearer " + accessToken(userId), body);
    }

    /** An access token for a signed-in session of the user, as the web app holds after signing in. */
    private String accessToken(long userId) {
        return accessTokens.computeIfAbsent(userId, id -> {
            jdbcTemplate.update("INSERT INTO users (user_id, username) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    id, "user" + id);
            return jwtTokenService.issueToken(id, userSessionService.start(id, "e2e").session().getSessionId());
        });
    }

    private Response callRaw(String method, String path, String authorization, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = null;
        if (!response.body().isBlank()) {
            try {
                json = objectMapper.readTree(response.body());
            } catch (Exception ignored) {
                // non-JSON body
            }
        }
        return new Response(response.statusCode(), json, response.body());
    }

    /** A POST to a cookie-authenticated auth endpoint, as the web app sends it. */
    private HttpResponse<String> session(String path, String refreshCookie, String origin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (refreshCookie != null) {
            builder.header("Cookie", "kc_refresh=" + refreshCookie);
        }
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String refreshCookie(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("kc_refresh="))
                .map(value -> value.substring("kc_refresh=".length(), value.indexOf(';')))
                .findFirst()
                .orElse(null);
    }

    private HttpResponse<String> preflight(String path, String origin, String method) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", method)
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String randomSecret() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    record Response(int status, JsonNode json, String body) {
        Response expect(int expected) {
            assertEquals(expected, status, body);
            return this;
        }
    }

    /** One bootstrapped server with a Members role and a Mods role. */
    private final class World {
        final long serverId;
        final long membersRole;
        final long modsRole;
        final Map<String, Long> catalog = new HashMap<>();

        World(long serverId, long membersRole, long modsRole) {
            this.serverId = serverId;
            this.membersRole = membersRole;
            this.modsRole = modsRole;
        }

        String path(String suffix) {
            return "/api/servers/" + serverId + suffix;
        }

        String boardPath(long boardId, String suffix) {
            return path("/boards/" + boardId + suffix);
        }

        long createBoard(long userId, String name) throws Exception {
            return call("POST", path("/boards"), userId, Map.of(
                    "name", name, "serverId", serverId, "columnNames", List.of("Todo", "Doing")))
                    .expect(201).json().get("boardId").asLong();
        }

        long column(long boardId, String name) throws Exception {
            for (JsonNode column : call("GET", boardPath(boardId, "/columns"), OWNER, null).expect(200).json()) {
                if (name.equals(column.get("name").asText())) {
                    return column.get("columnId").asLong();
                }
            }
            throw new AssertionError("No column " + name);
        }

        long createTask(long userId, long boardId, long columnId, String title) throws Exception {
            return call("POST", boardPath(boardId, "/tasks"), userId,
                    Map.of("title", title, "boardId", boardId, "columnId", columnId))
                    .expect(201).json().get("taskId").asLong();
        }

        int createTaskStatus(long userId, long boardId, long columnId) throws Exception {
            return call("POST", boardPath(boardId, "/tasks"), userId,
                    Map.of("title", "t", "boardId", boardId, "columnId", columnId)).status();
        }

        Response updateTask(long userId, long boardId, long taskId, String title, long columnId) throws Exception {
            return call("PUT", boardPath(boardId, "/tasks/" + taskId), userId,
                    Map.of("title", title, "boardId", boardId, "columnId", columnId));
        }

        Response createRule(long userId, String scopeType, long scopeId, String subjectType, long subjectId,
                String key, String state) throws Exception {
            return call("POST", path("/permissions"), userId, Map.of(
                    "scopeType", scopeType, "scopeId", scopeId, "subjectType", subjectType, "subjectId", subjectId,
                    "kanbanPermissionId", catalog.get(key), "state", state, "priority", 100));
        }

        Response moveTask(long userId, long boardId, long taskId, long columnId, int index) throws Exception {
            return call("POST", boardPath(boardId, "/tasks/" + taskId + "/move"), userId,
                    Map.of("columnId", columnId, "index", index));
        }

        JsonNode snapshot(long userId, long boardId) throws Exception {
            return call("GET", boardPath(boardId, "/snapshot"), userId, null).expect(200).json();
        }

        /** Task ids per column, in position order; empty columns are left out. */
        Map<Long, List<Long>> tasksByColumn(long boardId) throws Exception {
            Map<Long, List<JsonNode>> grouped = new HashMap<>();
            for (JsonNode task : snapshot(OWNER, boardId).get("tasks")) {
                grouped.computeIfAbsent(task.get("columnId").asLong(), ignored -> new ArrayList<>()).add(task);
            }
            Map<Long, List<Long>> ids = new HashMap<>();
            grouped.forEach((column, tasks) -> ids.put(column, tasks.stream()
                    .sorted(java.util.Comparator.comparing(t -> t.get("position").decimalValue()))
                    .map(t -> t.get("taskId").asLong())
                    .toList()));
            return ids;
        }

        void renameBoard(long boardId, String name) throws Exception {
            call("PUT", boardPath(boardId, ""), OWNER,
                    Map.of("name", name, "description", "", "serverId", serverId)).expect(200);
        }

        String topic(long boardId) {
            return "/topic/servers/" + serverId + "/boards/" + boardId;
        }

        List<Long> visibleBoards(long userId) throws Exception {
            List<Long> ids = new ArrayList<>();
            for (JsonNode board : call("GET", path("/boards?size=100"), userId, null).expect(200).json().get("content")) {
                ids.add(board.get("boardId").asLong());
            }
            return ids;
        }
    }

    /** A STOMP client authenticated with a realtime ticket, as the web client does. */
    private static final class RealtimeProbe {
        final BlockingQueue<Map<?, ?>> events = new LinkedBlockingQueue<>();
        final CompletableFuture<String> failure = new CompletableFuture<>();
        private StompSession session;

        /** The next event, waiting up to 10 seconds. */
        Map<?, ?> next() throws InterruptedException {
            Map<?, ?> event = events.poll(10, TimeUnit.SECONDS);
            if (event == null) {
                throw new AssertionError("No realtime event within 10 seconds");
            }
            return event;
        }

        /** Every event that arrives within the next second. */
        List<Map<?, ?>> drain() throws InterruptedException {
            Thread.sleep(1_000);
            List<Map<?, ?>> drained = new ArrayList<>();
            events.drainTo(drained);
            return drained;
        }

        static RealtimeProbe connect(EndToEndApiIntegrationTest test, long userId) throws Exception {
            return connect(test, "Bearer " + test.accessToken(userId));
        }

        static RealtimeProbe connect(EndToEndApiIntegrationTest test, String authorization) throws Exception {
            JsonNode ticket = test.callRaw("POST", "/api/realtime/tickets", authorization, null).expect(200).json();
            RealtimeProbe probe = new RealtimeProbe();

            WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
            client.setMessageConverter(new MappingJackson2MessageConverter());
            String url = "ws://localhost:" + test.port + ticket.get("websocketPath").asText()
                    + "?ticket=" + ticket.get("ticket").asText();
            probe.session = client.connectAsync(url, new StompSessionHandlerAdapter() {
                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    probe.failure.complete("ERROR frame: " + headers.getFirst("message"));
                }

                @Override
                public void handleException(StompSession s, StompCommand command, StompHeaders headers,
                        byte[] payload, Throwable exception) {
                    probe.failure.complete("exception: " + exception);
                }

                @Override
                public void handleTransportError(StompSession s, Throwable exception) {
                    probe.failure.complete("transport: " + exception);
                }
            }).get(10, TimeUnit.SECONDS);
            return probe;
        }

        void subscribe(String destination) {
            session.subscribe(destination, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return Map.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    events.add((Map<?, ?>) payload);
                }
            });
        }
    }
}
