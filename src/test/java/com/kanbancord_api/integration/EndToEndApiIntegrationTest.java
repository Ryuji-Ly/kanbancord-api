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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        // Notifications are grouped for a while in production; tests want them straight away.
        registry.add("kanbancord.notifications.group-window-seconds", () -> "0");
        // Tests run the reminder check themselves, when they want it.
        registry.add("kanbancord.notifications.reminder-first-check-ms", () -> String.valueOf(24L * 60 * 60 * 1000));
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
    @Autowired
    private com.kanbancord_api.notify.DueReminderScheduler dueReminderScheduler;

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

    // ── Discord notifications ───────────────────────────────────────────────────

    @Test
    void notifications_routeChangesToFeedsTheAuditChannelAndDirectMessages() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Notified");
        long other = w.createBoard(OWNER, "Elsewhere");
        long todo = w.column(board, "Todo");
        long doing = w.column(board, "Doing");
        String feeds = w.path("/notifications/feeds");
        String updates = "900" + w.serverId;
        String everything = "901" + w.serverId;
        String audit = "902" + w.serverId;

        // The bot reports the server's channels; only server administrators see and change settings.
        assertEquals(204, sync("PUT", "/api/internal/sync/servers/" + w.serverId + "/channels", List.of(
                Map.of("channelId", updates, "name", "updates", "position", 1, "botCanPost", true),
                Map.of("channelId", everything, "name", "everything", "position", 2, "botCanPost", true),
                Map.of("channelId", audit, "name", "audit-log", "category", "Staff", "position", 3, "botCanPost", true))));
        assertEquals(403, call("GET", w.path("/notifications"), MEMBER, null).status());
        JsonNode settings = call("GET", w.path("/notifications"), OWNER, null).expect(200).json();
        assertEquals(3, settings.get("channels").size());
        assertEquals("audit-log", settings.get("channels").get(2).get("name").asText());
        assertTrue(settings.get("catalogue").size() >= 5);

        assertEquals(400, call("PUT", w.path("/notifications/audit-channel"), OWNER, Map.of("channelId", "12345")).status(),
                "only channels of this server");
        call("PUT", w.path("/notifications/audit-channel"), OWNER, Map.of("channelId", audit)).expect(200);
        // A board feed that mentions people for comments too, and a server-wide one that mentions nobody.
        long boardFeed = call("POST", feeds, OWNER, Map.of("channelId", updates, "boardIds", List.of(board),
                "mentions", Map.of("COMMENTS", true))).expect(201).json().get("feedId").asLong();
        call("POST", feeds, OWNER, Map.of("channelId", everything,
                "mentions", Map.of("PEOPLE", false))).expect(201);
        assertEquals(400, call("POST", feeds, OWNER, Map.of("channelId", updates, "boardIds", List.of(999_999_999L))).status());
        assertEquals(400, call("POST", feeds, OWNER, Map.of("channelId", updates, "events", Map.of("NOPE", true))).status());
        drainPlans(w.serverId);

        // MOD creates a task, assigns MEMBER and comments: one group, delivered together.
        long task = w.createTask(MOD, board, todo, "Ship it");
        call("POST", w.boardPath(board, "/tasks/" + task + "/assignments"), MOD, Map.of("taskId", task, "userId", MEMBER))
                .expect(201);
        call("POST", w.boardPath(board, "/tasks/" + task + "/comments"), MOD, Map.of("taskId", task, "content", "Go"))
                .expect(201);
        List<JsonNode> plans = drainPlans(w.serverId);
        assertEquals(1, plans.size(), "changes to one task are grouped");
        JsonNode plan = plans.get(0);
        assertEquals("Ship it", plan.get("task").get("title").asText());
        assertEquals(3, plan.get("entries").size());
        assertEquals("Todo", plan.get("names").get("columns").get(String.valueOf(todo)).asText());

        JsonNode toUpdates = channel(plan, updates);
        assertEquals(3, toUpdates.get("entryIds").size());
        assertEquals(List.of(String.valueOf(MEMBER)), texts(toUpdates.get("mentionUserIds")),
                "the assignee is mentioned; the person who made the changes never is");
        assertEquals(List.of(), texts(channel(plan, everything).get("mentionUserIds")));
        JsonNode toAudit = channel(plan, audit);
        assertEquals("AUDIT", toAudit.get("kind").asText());
        assertEquals(3, toAudit.get("entryIds").size());

        JsonNode dm = directMessage(plan, MEMBER);
        assertEquals("UNLESS_PINGED", dm.get("mode").asText());
        assertEquals(2, dm.get("entryIds").size(), "being assigned, and the new comment on their task");
        assertNull(directMessage(plan, MOD), "never about your own changes");
        assertNull(directMessage(plan, OWNER), "not about tasks you have nothing to do with");

        // Reordering within a column goes to the audit channel only; other boards stay out of the board feed.
        long second = w.createTask(MOD, board, todo, "Second");
        drainPlans(w.serverId);
        call("POST", w.boardPath(board, "/tasks/" + second + "/move"), MOD, Map.of("columnId", todo, "index", 0)).expect(200);
        plan = drainPlans(w.serverId).get(0);
        assertNull(channel(plan, updates));
        assertNull(channel(plan, everything));
        assertEquals(1, channel(plan, audit).get("entryIds").size());
        w.createTask(OWNER, other, w.column(other, "Todo"), "Other board");
        plan = drainPlans(w.serverId).get(0);
        assertNull(channel(plan, updates), "the board feed covers its own board only");
        assertEquals(1, channel(plan, everything).get("entryIds").size());

        // MEMBER turns this server's direct messages off; the feeds still announce the change.
        call("PUT", "/api/me/notifications", MEMBER, Map.of("servers", Map.of(String.valueOf(w.serverId), "NONE")))
                .expect(200);
        w.updateTask(MOD, board, task, "Ship it", doing).expect(200);
        plan = drainPlans(w.serverId).get(0);
        assertNull(directMessage(plan, MEMBER));
        assertEquals(1, channel(plan, updates).get("entryIds").size());
        call("PUT", "/api/me/notifications", MEMBER, Map.of("servers", Map.of(String.valueOf(w.serverId), "DEFAULT"),
                "dmMode", "ALWAYS")).expect(200);
        JsonNode mine = call("GET", "/api/me/notifications", MEMBER, null).expect(200).json();
        assertEquals("ALWAYS", mine.get("dmMode").asText());
        assertEquals(400, call("PUT", "/api/me/notifications", MEMBER, Map.of("events", Map.of("COLUMN_CHANGED", true)))
                .status(), "board changes are never sent by direct message");

        // Deleting the task: the people who were assigned still hear about it.
        call("DELETE", w.boardPath(board, "/tasks/" + task), OWNER, null).expect(204);
        plan = drainPlans(w.serverId).get(0);
        assertTrue(plan.get("task").get("deleted").asBoolean());
        assertEquals("ALWAYS", directMessage(plan, MEMBER).get("mode").asText());

        // Changes the bot could not deliver for over an hour are dropped, not posted late all at once.
        w.createTask(MOD, board, todo, "Stale");
        jdbcTemplate.update("UPDATE notification_queue SET created_at = created_at - INTERVAL '2 hours' "
                + "WHERE server_id = ? AND delivered_at IS NULL", w.serverId);
        assertEquals(List.of(), drainPlans(w.serverId));

        // Feeds can be changed and removed; the changes are in the audit log.
        call("PUT", feeds + "/" + boardFeed, OWNER, Map.of("events", Map.of("TASK_CREATED", false))).expect(200);
        call("DELETE", feeds + "/" + boardFeed, OWNER, null).expect(204);
        assertTrue(auditLog(w).stream().anyMatch(e -> "NOTIFICATIONS_UPDATED".equals(e.get("action").asText())));
    }

    @Test
    void dueReminders_remindTheTasksPeopleOnce_whenDueSoonOrJustOverdue() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Deadlines");
        long todo = w.column(board, "Todo");
        long done = w.column(board, "Doing"); // the last column counts as done
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).withNano(0);

        long soon = dueTask(w, board, todo, "Soon", now.plusHours(2));
        call("POST", w.boardPath(board, "/tasks/" + soon + "/assignments"), MOD, Map.of("taskId", soon, "userId", MEMBER))
                .expect(201);
        long finished = dueTask(w, board, done, "Finished", now.plusHours(2));
        long overdue = dueTask(w, board, todo, "Just overdue", now.minusMinutes(10));
        long longAgo = dueTask(w, board, todo, "Long overdue", now.minusHours(3));
        long later = dueTask(w, board, todo, "Next week", now.plusDays(7));
        call("PUT", w.path("/notifications/audit-channel"), OWNER, Map.of("channelId", "")).expect(200);
        assertEquals(204, sync("PUT", "/api/internal/sync/servers/" + w.serverId + "/channels", List.of(
                Map.of("channelId", "903" + w.serverId, "name", "audit", "botCanPost", true))));
        call("PUT", w.path("/notifications/audit-channel"), OWNER, Map.of("channelId", "903" + w.serverId)).expect(200);
        drainPlans(w.serverId);

        dueReminderScheduler.remind();
        Map<Long, JsonNode> byTask = new HashMap<>();
        for (JsonNode plan : drainPlans(w.serverId)) {
            byTask.put(plan.get("task").get("taskId").asLong(), plan);
        }
        assertEquals(Set.of(soon, overdue), byTask.keySet(),
                "not for done tasks, long-overdue ones or ones due next week (" + finished + ", " + longAgo + ", " + later + ")");
        JsonNode reminder = byTask.get(soon);
        assertEquals("TASK_DUE_SOON", reminder.get("entries").get(0).get("action").asText());
        assertNotNull(directMessage(reminder, MEMBER), "the assignee is reminded");
        assertNotNull(directMessage(reminder, MOD), "and whoever created the task");
        assertNull(channel(reminder, "903" + w.serverId), "reminders are not something anyone did: not in the audit channel");
        assertEquals("TASK_OVERDUE", byTask.get(overdue).get("entries").get(0).get("action").asText());

        // Once per due date: nothing more now, a new reminder after the due date changes.
        dueReminderScheduler.remind();
        assertEquals(List.of(), drainPlans(w.serverId));
        call("PUT", w.boardPath(board, "/tasks/" + soon), MOD, Map.of("title", "Soon", "boardId", board, "columnId", todo,
                "dueDate", now.plusHours(5).toString())).expect(200);
        drainPlans(w.serverId);
        dueReminderScheduler.remind();
        assertEquals(1, drainPlans(w.serverId).size());

        // Boards without due dates get no reminders.
        call("PUT", w.boardPath(board, "/features"), OWNER, Map.of("DUE_DATES", false)).expect(200);
        call("PUT", w.boardPath(board, "/tasks/" + soon), MOD, Map.of("title", "Soon", "boardId", board, "columnId", todo,
                "dueDate", now.plusHours(6).toString())).expect(200);
        drainPlans(w.serverId);
        dueReminderScheduler.remind();
        assertEquals(List.of(), drainPlans(w.serverId));
    }

    private long dueTask(World w, long board, long column, String title, java.time.LocalDateTime due) throws Exception {
        return call("POST", w.boardPath(board, "/tasks"), MOD, Map.of("title", title, "boardId", board, "columnId", column,
                "dueDate", due.toString())).expect(201).json().get("taskId").asLong();
    }

    /** Claims everything due, returning this server's plans and marking every claimed plan delivered. */
    @Test
    void following_bringsDirectMessages_andInteractiveFeedsAskForButtons() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Followed");
        long todo = w.column(board, "Todo");
        String plain = "910" + w.serverId;
        String buttons = "911" + w.serverId;
        assertEquals(204, sync("PUT", "/api/internal/sync/servers/" + w.serverId + "/channels", List.of(
                Map.of("channelId", plain, "name", "plain", "position", 1, "botCanPost", true),
                Map.of("channelId", buttons, "name", "buttons", "position", 2, "botCanPost", true))));
        String feeds = w.path("/notifications/feeds");
        assertTrue(call("POST", feeds, OWNER, Map.of("channelId", buttons)).expect(201).json().get("interactive").asBoolean(),
                "new feeds are interactive");
        long plainFeed = call("POST", feeds, OWNER, Map.of("channelId", plain, "interactive", false)).expect(201).json()
                .get("feedId").asLong();
        long task = w.createTask(MOD, board, todo, "Watch me");
        drainPlans(w.serverId);

        // Anyone who can see a task may follow it, from Discord or the website; it shows in their snapshot only.
        String follow = w.boardPath(board, "/tasks/" + task + "/follow");
        assertTrue(asBot("PUT", follow, BOT_TOKEN, OTHER, w.serverId, null).expect(200).json().get("following").asBoolean());
        call("PUT", follow, OTHER, null).expect(200);
        assertEquals(List.of(String.valueOf(task)),
                texts(call("GET", w.boardPath(board, "/snapshot"), OTHER, null).expect(200).json().get("followedTaskIds")));
        assertEquals(List.of(),
                texts(call("GET", w.boardPath(board, "/snapshot"), MEMBER, null).expect(200).json().get("followedTaskIds")));
        assertEquals(403, asBot("PUT", follow, BOT_TOKEN, 9_998L, w.serverId, null).status(), "only people who can see it");

        // A follower hears about the task like its people do; the interactive feed asks for buttons.
        call("POST", w.boardPath(board, "/tasks/" + task + "/comments"), MOD, Map.of("taskId", task, "content", "Hi"))
                .expect(201);
        JsonNode plan = drainPlans(w.serverId).get(0);
        assertNotNull(directMessage(plan, OTHER), "a follower is told");
        assertNull(directMessage(plan, MEMBER), "someone not following is not");
        assertTrue(channel(plan, buttons).get("interactive").asBoolean());
        assertFalse(channel(plan, plain).get("interactive").asBoolean());

        // Followed tasks can be switched off, like commented ones.
        assertTrue(call("GET", "/api/me/notifications", OTHER, null).expect(200).json().get("includeFollowed").asBoolean(),
                "on by default: following is asking to hear");
        call("PUT", "/api/me/notifications", OTHER, Map.of("includeFollowed", false)).expect(200);
        call("POST", w.boardPath(board, "/tasks/" + task + "/comments"), MOD, Map.of("taskId", task, "content", "Again"))
                .expect(201);
        assertNull(directMessage(drainPlans(w.serverId).get(0), OTHER));
        call("PUT", "/api/me/notifications", OTHER, Map.of("includeFollowed", true)).expect(200);

        // Unfollowing is idempotent.
        assertFalse(asBot("DELETE", follow, BOT_TOKEN, OTHER, w.serverId, null).expect(200).json().get("following").asBoolean());
        call("DELETE", follow, OTHER, null).expect(200);
        assertEquals(List.of(),
                texts(call("GET", w.boardPath(board, "/snapshot"), OTHER, null).expect(200).json().get("followedTaskIds")));

        // A deleted task has nothing to show buttons for; switching a feed back to plain is remembered.
        call("DELETE", w.boardPath(board, "/tasks/" + task), OWNER, null).expect(204);
        assertFalse(channel(drainPlans(w.serverId).get(0), buttons).get("interactive").asBoolean());
        assertTrue(call("PUT", feeds + "/" + plainFeed, OWNER, Map.of("interactive", true)).expect(200).json()
                .get("interactive").asBoolean());
    }

    @Test
    void feedMentions_arePerEvent_categoriesSetAllTheirEvents_andOldFeedsKeepTheirs() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Mentions");
        long todo = w.column(board, "Todo");
        long doing = w.column(board, "Doing");
        String updates = "920" + w.serverId;
        assertEquals(204, sync("PUT", "/api/internal/sync/servers/" + w.serverId + "/channels", List.of(
                Map.of("channelId", updates, "name", "updates", "position", 1, "botCanPost", true))));
        String feeds = w.path("/notifications/feeds");

        // New feeds mention people for what they need to act on, not for every edit.
        JsonNode feed = call("POST", feeds, OWNER, Map.of("channelId", updates)).expect(201).json();
        long feedId = feed.get("feedId").asLong();
        assertTrue(feed.get("mentions").get("TASK_MOVED").asBoolean());
        assertTrue(feed.get("mentions").get("TASK_DUE").asBoolean());
        assertTrue(feed.get("mentions").get("USER_ASSIGNED").asBoolean());
        assertFalse(feed.get("mentions").get("TASK_CREATED").asBoolean());
        assertFalse(feed.get("mentions").get("TASK_TITLE").asBoolean());
        assertFalse(feed.get("mentions").get("COLUMN_CHANGED").asBoolean());
        JsonNode catalogue = call("GET", w.path("/notifications"), OWNER, null).expect(200).json().get("catalogue");
        JsonNode created = catalogue.get(0).get("events").get(0);
        assertEquals("TASK_CREATED", created.get("key").asText());
        assertTrue(created.get("canMention").asBoolean());
        assertFalse(created.get("mentionDefault").asBoolean());

        // A category sets all its events; single events in the same request win.
        JsonNode changed = call("PUT", feeds + "/" + feedId, OWNER, Map.of("mentions",
                Map.of("TASKS", true, "TASK_TITLE", false))).expect(200).json().get("mentions");
        assertTrue(changed.get("TASK_CREATED").asBoolean());
        assertFalse(changed.get("TASK_TITLE").asBoolean());
        assertEquals(400, call("PUT", feeds + "/" + feedId, OWNER, Map.of("mentions", Map.of("COLUMN_CHANGED", true))).status(),
                "board-wide changes concern nobody in particular");
        assertEquals(400, call("PUT", feeds + "/" + feedId, OWNER, Map.of("mentions", Map.of("NOPE", true))).status());

        // Only the events set to mention ping the task's people.
        call("PUT", feeds + "/" + feedId, OWNER, Map.of("mentions", Map.of("TASKS", false, "TASK_MOVED", true))).expect(200);
        long task = w.createTask(OWNER, board, todo, "Ping on move");
        call("POST", w.boardPath(board, "/tasks/" + task + "/assignments"), OWNER, Map.of("taskId", task, "userId", MEMBER))
                .expect(201);
        drainPlans(w.serverId);
        call("PUT", w.boardPath(board, "/tasks/" + task), OWNER,
                Map.of("title", "Renamed", "boardId", board, "columnId", todo)).expect(200);
        assertEquals(List.of(), texts(channel(drainPlans(w.serverId).get(0), updates).get("mentionUserIds")),
                "a new title is posted without pinging anyone");
        call("POST", w.boardPath(board, "/tasks/" + task + "/move"), OWNER, Map.of("columnId", doing, "index", 0))
                .expect(200);
        assertEquals(List.of(String.valueOf(MEMBER)),
                texts(channel(drainPlans(w.serverId).get(0), updates).get("mentionUserIds")), "a move pings the assignee");

        // A feed saved when mentions were per category reads as those categories' events.
        jdbcTemplate.update("UPDATE notification_feeds SET mentions = CAST(? AS JSONB) WHERE feed_id = ?",
                "{\"TASKS\": true, \"PEOPLE\": false}", feedId);
        JsonNode old = call("GET", w.path("/notifications"), OWNER, null).expect(200).json().get("feeds").get(0).get("mentions");
        assertTrue(old.get("TASK_CREATED").asBoolean());
        assertTrue(old.get("TASK_MOVED").asBoolean());
        assertFalse(old.get("USER_ASSIGNED").asBoolean());
        assertFalse(old.get("COMMENT_CREATED").asBoolean(), "categories it did not mention keep the default");
    }

    @Test
    void openPermissions_letEveryoneWhoCanTalkWorkWithBoards_butNotManageTheServer() throws Exception {
        World w = bootstrapServer(true);
        long board = w.createBoard(OWNER, "Open");
        long todo = w.column(board, "Todo");
        long task = w.createTask(OWNER, board, todo, "Anyone may change this");
        String open = w.path("/features/open-permissions");
        Map<String, Object> column = Map.of("name", "Review", "boardId", board);

        // Off by default: a member cannot manage columns or edit someone else's task.
        assertFalse(call("GET", open, MEMBER, null).expect(200).json().get("enabled").asBoolean());
        assertEquals(403, call("POST", w.boardPath(board, "/columns"), MEMBER, column).status());
        assertEquals(403, call("PUT", open, MEMBER, Map.of("enabled", true)).status(), "only managers switch it");

        // It cannot be on together with custom permissions, whose rules would no longer apply.
        assertEquals(400, call("PUT", open, OWNER, Map.of("enabled", true)).status());
        call("PUT", w.path("/features"), OWNER, Map.of("PERMISSIONS", false)).expect(200);
        assertTrue(call("PUT", open, OWNER, Map.of("enabled", true)).expect(200).json().get("enabled").asBoolean());
        assertEquals(400, call("PUT", w.path("/features"), OWNER, Map.of("PERMISSIONS", true)).status());
        assertEquals(200, call("PUT", w.path("/features"), OWNER, Map.of("LABELS", false)).status(),
                "other features switch as usual");

        // On: anyone who can talk works with boards, columns and tasks.
        call("POST", w.boardPath(board, "/columns"), MEMBER, column).expect(201);
        call("PUT", w.boardPath(board, "/tasks/" + task), MEMBER,
                Map.of("title", "Changed by a member", "boardId", board, "columnId", todo)).expect(200);
        call("POST", w.path("/boards"), MEMBER, Map.of("name", "Members' board", "serverId", w.serverId)).expect(201);
        JsonNode decision = call("GET", w.boardPath(board, "/snapshot"), MEMBER, null).expect(200).json()
                .get("permissions").get("DELETE_COLUMN");
        assertTrue(decision.get("allowed").asBoolean());
        assertEquals("OPEN", decision.get("sourceTier").asText());

        // Managing the server stays with its managers.
        assertEquals(403, call("DELETE", w.path("/boards/" + board), MEMBER, null).status(), "deleting a board");
        assertEquals(403, call("PUT", w.path("/features"), MEMBER, Map.of("LABELS", true)).status(), "features");
        assertEquals(403, call("GET", w.path("/audit-logs"), MEMBER, null).status(), "the audit log");

        // Someone who cannot send messages is not "everyone who can talk".
        assertEquals(403, w.createTaskStatus(NEWBIE, board, todo));

        // Off again: back to the rules.
        call("PUT", open, OWNER, Map.of("enabled", false)).expect(200);
        assertEquals(403, call("POST", w.boardPath(board, "/columns"), MEMBER, Map.of("name", "Again", "boardId", board))
                .status());
    }

    @Test
    void customPermissionsOff_theDefaultsApply_andTheRulesAreKeptForLater() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Kept");
        long todo = w.column(board, "Todo");
        w.createRule(OWNER, "SERVER", w.serverId, "ROLE", w.membersRole, "CREATE_TASK", "DENY").expect(201);
        w.createRule(OWNER, "BOARD", board, "ROLE", w.membersRole, "VIEW_BOARD", "DENY").expect(201);
        assertEquals(403, w.createTaskStatus(MEMBER, board, todo));

        // Off: what Discord permissions give, as for a new server. The rules stay, unused.
        call("PUT", w.path("/features"), OWNER, Map.of("PERMISSIONS", false)).expect(200);
        assertEquals(201, w.createTaskStatus(MEMBER, board, todo), "Send Messages creates tasks");
        assertEquals(200, call("GET", w.boardPath(board, "/snapshot"), MEMBER, null).status(), "no board rules");
        assertEquals(2, count("SELECT COUNT(*) FROM permissions WHERE subject_type = 'ROLE' AND subject_id = "
                + w.membersRole));

        // Syncing the server again leaves its rules as they are.
        assertEquals(204, sync("POST", "/api/internal/sync/servers/" + w.serverId + "/bootstrap", Map.of(
                "name", "E2E " + w.serverId, "ownerId", OWNER, "ownerUsername", "owner",
                "roles", List.of(), "members", List.of())));

        // On again: the rules apply again.
        call("PUT", w.path("/features"), OWNER, Map.of("PERMISSIONS", true)).expect(200);
        assertEquals(403, w.createTaskStatus(MEMBER, board, todo));
        assertEquals(403, call("GET", w.boardPath(board, "/snapshot"), MEMBER, null).status());
    }

    @Test
    void accessCheck_saysWhatSomeoneMayDoAndWhy_forMembersOtherRolesAndRoleSets() throws Exception {
        World w = bootstrapServer(true);
        long board = w.createBoard(OWNER, "Checked");
        w.createRule(OWNER, "BOARD", board, "ROLE", w.membersRole, "DELETE_TASK", "ALLOW").expect(201);
        w.createRule(OWNER, "BOARD", board, "USER", MOD, "EDIT_BOARD_PERMISSIONS", "ALLOW").expect(201);
        String check = w.path("/permissions/check");

        // Yourself, server-wide: Send Messages lets you create tasks; nothing lets you delete them.
        JsonNode self = call("GET", check, MEMBER, null).expect(200).json();
        assertEquals("member", self.get("subject").get("name").asText());
        JsonNode create = result(self, "CREATE_TASK");
        assertTrue(create.get("allowed").asBoolean());
        assertEquals("RULE", create.get("reason").asText());
        assertEquals("SEND_MESSAGES", create.get("decidedBy").get("subjectName").asText());
        assertEquals("NONE", result(self, "DELETE_TASK").get("reason").asText());

        // On the board, its rule for Members decides.
        JsonNode onBoard = call("GET", check + "?boardId=" + board, MEMBER, null).expect(200).json();
        JsonNode delete = result(onBoard, "DELETE_TASK");
        assertTrue(delete.get("allowed").asBoolean());
        assertEquals("BOARD", delete.get("decidedBy").get("scope").asText());
        assertEquals("Members", delete.get("decidedBy").get("subjectName").asText());

        // Checking someone else takes managing permissions, or on a board, editing its permissions.
        assertEquals(403, call("GET", check + "?userId=" + MOD, MEMBER, null).status());
        assertEquals(403, call("GET", check + "?userId=" + MEMBER, MOD, null).status());
        call("GET", check + "?userId=" + MEMBER + "&boardId=" + board, MOD, null).expect(200);

        // A member with other roles: Mods gives Manage Messages, so editing tasks.
        JsonNode whatIf = call("GET", check + "?userId=" + MEMBER + "&withRoles=true&roleIds=" + w.membersRole
                + "," + w.modsRole, OWNER, null).expect(200).json();
        assertTrue(whatIf.get("subject").get("rolesChanged").asBoolean());
        assertTrue(result(whatIf, "EDIT_TASK").get("allowed").asBoolean());

        // Anyone with no roles but @everyone: they can look, not change.
        JsonNode nobody = call("GET", check + "?withRoles=true", OWNER, null).expect(200).json();
        assertTrue(nobody.get("subject").get("userId").isNull());
        assertTrue(nobody.get("subject").get("roles").get(0).get("everyone").asBoolean());
        assertTrue(result(nobody, "VIEW_BOARD").get("allowed").asBoolean());
        assertFalse(result(nobody, "CREATE_TASK").get("allowed").asBoolean());
        assertEquals(400, call("GET", check + "?withRoles=true&roleIds=123", OWNER, null).status(), "not a role here");

        // The owner may do everything.
        JsonNode owner = call("GET", check, OWNER, null).expect(200).json();
        assertTrue(owner.get("subject").get("administrator").asBoolean());
        assertEquals("ADMIN", result(owner, "DELETE_BOARD").get("reason").asText());
        assertEquals(400, w.createRule(OWNER, "SERVER", w.serverId, "USER", OWNER, "ADMIN", "DENY").status(),
                "administrators cannot be locked out");

        // With custom permissions off, the board's rule does not apply, and the defaults are built in.
        call("PUT", w.path("/features"), OWNER, Map.of("PERMISSIONS", false)).expect(200);
        JsonNode off = call("GET", check + "?boardId=" + board, MEMBER, null).expect(200).json();
        assertFalse(off.get("customPermissions").asBoolean());
        assertFalse(result(off, "DELETE_TASK").get("allowed").asBoolean());
        assertTrue(result(off, "CREATE_TASK").get("decidedBy").get("builtIn").asBoolean());
    }

    private static JsonNode result(JsonNode check, String key) {
        for (JsonNode result : check.get("results")) {
            if (key.equals(result.get("key").asText())) {
                return result;
            }
        }
        throw new AssertionError("No result for " + key);
    }

    @Test
    void channelBoards_namesTheBoardsPostedInAChannel_thatTheCallerMayAddTasksTo() throws Exception {
        World w = bootstrapServer();
        long sprint = w.createBoard(OWNER, "Sprint");
        long staff = w.createBoard(OWNER, "Staff");
        long elsewhere = w.createBoard(OWNER, "Elsewhere");
        for (long board : List.of(sprint, staff)) {
            call("POST", w.boardPath(board, "/posts"), OWNER, Map.of("channelId", "5001", "messageId", w.serverId + "0" + board))
                    .expect(201);
        }
        call("POST", w.boardPath(elsewhere, "/posts"), OWNER, Map.of("channelId", "5002", "messageId", w.serverId + "7001")).expect(201);
        w.createRule(OWNER, "BOARD", staff, "ROLE", w.membersRole, "CREATE_TASK", "DENY").expect(201);

        JsonNode mine = call("GET", w.path("/channels/5001/boards"), MEMBER, null).expect(200).json();
        assertEquals(List.of(sprint), objectMapper.convertValue(mine.get("boardIds"), new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() { }));
        JsonNode owners = call("GET", w.path("/channels/5001/boards"), OWNER, null).expect(200).json();
        assertEquals(2, owners.get("boardIds").size());
    }

    @Test
    void taskThreads_areOnPerBoard_inAFeedChannel_madeByTheBot_andCloseWithTheTask() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Threaded");
        long todo = w.column(board, "Todo");
        String feedChannel = "931" + w.serverId;
        String announcements = "932" + w.serverId;
        String other = "933" + w.serverId;
        assertEquals(204, sync("PUT", "/api/internal/sync/servers/" + w.serverId + "/channels", List.of(
                Map.of("channelId", feedChannel, "name", "updates", "position", 1, "botCanPost", true,
                        "botCanThread", true, "botCanPrivateThread", true),
                Map.of("channelId", announcements, "name", "news", "position", 2, "botCanPost", true,
                        "botCanThread", true, "botCanPrivateThread", false),
                Map.of("channelId", other, "name", "other", "position", 3, "botCanPost", true))));
        call("POST", w.path("/notifications/feeds"), OWNER, Map.of("channelId", feedChannel, "boardIds", List.of(board)))
                .expect(201);
        call("POST", w.path("/notifications/feeds"), OWNER, Map.of("channelId", announcements, "boardIds", List.of()))
                .expect(201);
        String settings = w.boardPath(board, "/threads");

        // Off until switched on; for the board's managers; only in one of the board's feed channels.
        JsonNode off = call("GET", settings, OWNER, null).expect(200).json();
        assertFalse(off.get("enabled").asBoolean());
        assertEquals(2, off.get("channels").size(), "the board's own feed and the every-board feed");
        assertEquals(403, call("PUT", settings, MEMBER, Map.of("channelId", feedChannel)).status());
        assertEquals(400, call("PUT", settings, OWNER, Map.of("channelId", other)).status(), "no feed for the board there");
        assertEquals(400, call("PUT", settings, OWNER, Map.of("channelId", announcements, "privateThreads", true)).status(),
                "no private threads where the bot cannot make them");
        JsonNode on = call("PUT", settings, OWNER, Map.of("channelId", feedChannel)).expect(200).json();
        assertTrue(on.get("enabled").asBoolean() && on.get("active").asBoolean());
        assertEquals("BOTH", on.get("updates").asText(), "updates go to both by default");
        assertFalse(on.get("privateThreads").asBoolean(), "public by default");
        drainPlans(w.serverId);

        // A new task: the bot is asked to make its thread, and reports it.
        long task = w.createTask(OWNER, board, todo, "Discuss me");
        JsonNode thread = drainPlans(w.serverId).get(0).get("thread");
        assertEquals(feedChannel, thread.get("channelId").asText());
        assertTrue(thread.get("threadId").isNull(), "made by the bot");
        assertEquals("Discuss me", thread.get("name").asText());
        internal("POST", "/api/internal/notifications/threads", Map.of("serverId", String.valueOf(w.serverId),
                "taskId", task, "channelId", feedChannel, "threadId", "7777", "privateThread", false)).expect(204);

        // Later changes know the thread, and where updates go.
        call("PUT", settings, OWNER, Map.of("channelId", feedChannel, "updates", "THREAD")).expect(200);
        w.updateTask(OWNER, board, task, "Discuss me more", todo).expect(200);
        JsonNode later = drainPlans(w.serverId).get(0).get("thread");
        assertEquals("7777", later.get("threadId").asText());
        assertEquals("THREAD", later.get("updates").asText());
        assertEquals("Discuss me more", later.get("name").asText());

        // A thread deleted in Discord is forgotten, so the task gets a new one.
        internal("POST", "/api/internal/notifications/threads", Map.of("serverId", String.valueOf(w.serverId),
                "taskId", task, "channelId", feedChannel, "threadId", "7777", "gone", true)).expect(204);
        w.updateTask(OWNER, board, task, "Again", todo).expect(200);
        assertTrue(drainPlans(w.serverId).get(0).get("thread").get("threadId").isNull());
        internal("POST", "/api/internal/notifications/threads", Map.of("serverId", String.valueOf(w.serverId),
                "taskId", task, "channelId", feedChannel, "threadId", "8888", "privateThread", false)).expect(204);

        // Private threads include the task's creator and assignees.
        call("PUT", settings, OWNER, Map.of("channelId", feedChannel, "privateThreads", true)).expect(200);
        long secret = w.createTask(OWNER, board, todo, "Secret");
        call("POST", w.boardPath(board, "/tasks/" + secret + "/assignments"), OWNER,
                Map.of("taskId", secret, "userId", MEMBER)).expect(201);
        JsonNode privateThread = drainPlans(w.serverId).get(0).get("thread");
        assertTrue(privateThread.get("privateThread").asBoolean());
        assertEquals(List.of(String.valueOf(OWNER), String.valueOf(MEMBER)), texts(privateThread.get("members")));

        // Switched off: no thread for new tasks, but a deleted task's thread is still closed.
        call("DELETE", settings, OWNER, null).expect(200);
        w.createTask(OWNER, board, todo, "Unthreaded");
        assertTrue(drainPlans(w.serverId).get(0).get("thread").isNull());
        call("DELETE", w.boardPath(board, "/tasks/" + task), OWNER, null).expect(204);
        JsonNode closing = drainPlans(w.serverId).get(0).get("thread");
        assertEquals("8888", closing.get("threadId").asText());
        assertTrue(closing.get("close").asBoolean());
        assertEquals("CHANNEL", closing.get("updates").asText(), "nothing more is posted in it");
    }

    @Test
    void discussInThread_tellsTheBotWhetherATaskHasAThread_andWhoMaySwitchThreadsOn() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Talky");
        long todo = w.column(board, "Todo");
        long task = w.createTask(OWNER, board, todo, "Talk about me");
        String channel = "934" + w.serverId;
        String info = w.boardPath(board, "/tasks/" + task + "/thread");

        // No feed: the board's tasks cannot have threads, and the button is not shown.
        assertFalse(w.snapshot(MEMBER, board).get("threads").get("available").asBoolean());
        assertFalse(call("GET", info, MEMBER, null).expect(200).json().get("available").asBoolean());

        assertEquals(204, sync("PUT", "/api/internal/sync/servers/" + w.serverId + "/channels", List.of(
                Map.of("channelId", channel, "name", "updates", "position", 1, "botCanPost", true,
                        "botCanThread", true, "botCanPrivateThread", true))));
        call("POST", w.path("/notifications/feeds"), OWNER, Map.of("channelId", channel, "boardIds", List.of(board)))
                .expect(201);
        JsonNode threads = w.snapshot(MEMBER, board).get("threads");
        assertTrue(threads.get("available").asBoolean());
        assertFalse(threads.get("enabled").asBoolean());

        // Threads off: a member is told so; whoever may edit the board may switch them on, and where.
        JsonNode member = call("GET", info, MEMBER, null).expect(200).json();
        assertFalse(member.get("enabled").asBoolean() || member.get("canEnable").asBoolean());
        assertEquals(0, member.get("channels").size());
        JsonNode owner = call("GET", info, OWNER, null).expect(200).json();
        assertTrue(owner.get("canEnable").asBoolean());
        assertEquals(channel, owner.get("channels").get(0).get("channelId").asText());

        // On: the bot is told the thread to make; once made, everyone sees it.
        call("PUT", w.boardPath(board, "/threads"), OWNER, Map.of("channelId", channel, "privateThreads", true)).expect(200);
        call("POST", w.boardPath(board, "/tasks/" + task + "/assignments"), OWNER,
                Map.of("taskId", task, "userId", MEMBER)).expect(201);
        JsonNode toMake = call("GET", info, MEMBER, null).expect(200).json();
        assertTrue(toMake.get("enabled").asBoolean() && toMake.get("threadId").isNull());
        assertEquals("Talk about me", toMake.get("name").asText());
        assertEquals(List.of(String.valueOf(OWNER), String.valueOf(MEMBER)), texts(toMake.get("members")));
        internal("POST", "/api/internal/notifications/threads", Map.of("serverId", String.valueOf(w.serverId),
                "taskId", task, "channelId", channel, "threadId", "9999", "privateThread", true)).expect(204);
        assertEquals("9999", call("GET", info, MEMBER, null).expect(200).json().get("threadId").asText());
        assertEquals("9999", w.snapshot(MEMBER, board).get("threads").get("threadIds").get(String.valueOf(task)).asText());
    }

    @Test
    void publicHealth_saysOnlyWhetherTheApiIsUp_withoutSigningIn() throws Exception {
        Response health = callRaw("GET", "/api/health", null, null).expect(200);
        assertEquals(Map.of("status", "UP"), objectMapper.convertValue(health.json(), Map.class),
                "no details, just up or down");
    }

    @Test
    void boardFeedSettings_changeWhatAFeedPostsForOneBoard_andTheServerSeesThem() throws Exception {
        World w = bootstrapServer();
        long quiet = w.createBoard(OWNER, "Quiet");
        long loud = w.createBoard(OWNER, "Loud");
        String updates = "930" + w.serverId;
        assertEquals(204, sync("PUT", "/api/internal/sync/servers/" + w.serverId + "/channels", List.of(
                Map.of("channelId", updates, "name", "updates", "position", 1, "botCanPost", true))));
        long feedId = call("POST", w.path("/notifications/feeds"), OWNER,
                Map.of("channelId", updates, "boardIds", List.of(quiet, loud))).expect(201).json().get("feedId").asLong();
        String quietSettings = w.boardPath(quiet, "/notifications");

        assertEquals(403, call("GET", quietSettings, MEMBER, null).status(), "for the board's managers");
        JsonNode feeds = call("GET", quietSettings, OWNER, null).expect(200).json().get("feeds");
        assertEquals(1, feeds.size());
        assertEquals("updates", feeds.get(0).get("channelName").asText());
        assertEquals(0, feeds.get(0).get("own").get("changes").asInt(), "follows the feed until changed");

        // The quiet board: no posts for new tasks, and moves without pinging anyone.
        JsonNode own = call("PUT", quietSettings + "/feeds/" + feedId, OWNER, Map.of(
                "events", Map.of("TASK_CREATED", false),
                "mentions", Map.of("TASK_MOVED", false))).expect(200).json().get("feeds").get(0).get("own");
        assertEquals(2, own.get("changes").asInt());
        assertEquals(400, call("PUT", quietSettings + "/feeds/" + feedId, OWNER,
                Map.of("mentions", Map.of("COLUMN_CHANGED", true))).status());
        assertEquals(2, call("GET", w.path("/notifications"), OWNER, null).expect(200).json().get("feeds").get(0)
                .get("boardOverrides").get(String.valueOf(quiet)).get("changes").asInt(), "the server sees the board's changes");
        drainPlans(w.serverId);

        w.createTask(OWNER, quiet, w.column(quiet, "Todo"), "Unannounced");
        assertTrue(drainPlans(w.serverId).stream().allMatch(plan -> channel(plan, updates) == null),
                "a new task on the quiet board is not posted");
        long loudTask = w.createTask(OWNER, loud, w.column(loud, "Todo"), "Announced");
        assertNotNull(channel(drainPlans(w.serverId).get(0), updates), "the other board still posts it");

        long quietTask = w.createTask(OWNER, quiet, w.column(quiet, "Todo"), "Moves quietly");
        call("POST", w.boardPath(quiet, "/tasks/" + quietTask + "/assignments"), OWNER,
                Map.of("taskId", quietTask, "userId", MEMBER)).expect(201);
        drainPlans(w.serverId);
        call("POST", w.boardPath(quiet, "/tasks/" + quietTask + "/move"), OWNER,
                Map.of("columnId", w.column(quiet, "Doing"), "index", 0)).expect(200);
        JsonNode moved = channel(drainPlans(w.serverId).get(0), updates);
        assertNotNull(moved, "the move is posted");
        assertEquals(List.of(), texts(moved.get("mentionUserIds")), "but pings nobody on this board");

        // Setting something back to the feed's value, or resetting, follows the feed again.
        assertEquals(1, call("PUT", quietSettings + "/feeds/" + feedId, OWNER, Map.of("events", Map.of("TASK_CREATED", true)))
                .expect(200).json().get("feeds").get(0).get("own").get("changes").asInt());
        assertEquals(0, call("DELETE", quietSettings + "/feeds/" + feedId, OWNER, null).expect(200).json()
                .get("feeds").get(0).get("own").get("changes").asInt());

        // A board the feed stops covering loses its own settings for it.
        call("PUT", quietSettings + "/feeds/" + feedId, OWNER, Map.of("events", Map.of("TASK_CREATED", false))).expect(200);
        call("PUT", w.path("/notifications/feeds/" + feedId), OWNER, Map.of("boardIds", List.of(loud))).expect(200);
        assertEquals(0, call("GET", quietSettings, OWNER, null).expect(200).json().get("feeds").size());
        assertEquals(400, call("PUT", quietSettings + "/feeds/" + feedId, OWNER,
                Map.of("events", Map.of("TASK_CREATED", false))).status(), "not a feed of this board any more");
        assertTrue(call("GET", w.path("/notifications"), OWNER, null).expect(200).json().get("feeds").get(0)
                .get("boardOverrides").isEmpty());
        assertTrue(loudTask > 0);
    }

    private List<JsonNode> drainPlans(long serverId) throws Exception {
        List<JsonNode> mine = new ArrayList<>();
        while (true) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/api/internal/notifications/claim?limit=50"))
                    .header("X-Internal-Bot-Token", BOT_TOKEN)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            JsonNode plans = objectMapper.readTree(response.body());
            if (plans.isEmpty()) {
                return mine;
            }
            for (JsonNode plan : plans) {
                if (plan.get("serverId").asText().equals(String.valueOf(serverId))) {
                    mine.add(plan);
                }
                http.send(HttpRequest.newBuilder(uri("/api/internal/notifications/" + plan.get("batchId").asLong() + "/delivered"))
                        .header("X-Internal-Bot-Token", BOT_TOKEN)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            }
        }
    }

    private static JsonNode channel(JsonNode plan, String channelId) {
        for (JsonNode delivery : plan.get("channels")) {
            if (delivery.get("channelId").asText().equals(channelId)) {
                return delivery;
            }
        }
        return null;
    }

    private static JsonNode directMessage(JsonNode plan, long userId) {
        for (JsonNode message : plan.get("directMessages")) {
            if (message.get("userId").asText().equals(String.valueOf(userId))) {
                return message;
            }
        }
        return null;
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
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
        assertEquals(403, asBot("GET", "/api/me/sessions", BOT_TOKEN, OWNER, w.serverId, null).status());
        assertEquals(200, asBot("PUT", "/api/me/notifications", BOT_TOKEN, MEMBER, w.serverId,
                Map.of("dmMode", "NEVER")).status(), "except the user's own notification settings");
        assertEquals("NEVER", call("GET", "/api/me/notifications", MEMBER, null).json().get("dmMode").asText());
        call("PUT", "/api/me/notifications", MEMBER, Map.of("dmMode", "UNLESS_PINGED")).expect(200);
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

    // ── Board posts ─────────────────────────────────────────────────────────────

    @Test
    void boardPosts_redrawAfterChanges_settleBursts_retryAndDisappear() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Posted");
        long todo = w.column(board, "Todo");
        String posts = w.boardPath(board, "/posts");

        assertEquals(403, asBot("POST", posts, BOT_TOKEN, MEMBER, w.serverId,
                Map.of("channelId", "5001", "messageId", "6001")).status(), "posting needs the right to edit the board");
        long postId = asBot("POST", posts, BOT_TOKEN, OWNER, w.serverId,
                Map.of("channelId", "5001", "messageId", "6001")).expect(201).json().get("postId").asLong();
        assertEquals(400, asBot("POST", posts, BOT_TOKEN, OWNER, w.serverId,
                Map.of("channelId", "5001", "messageId", "6001")).status(), "a message is one post");

        // A new post is drawn from the whole board straight away.
        JsonNode claimed = only(claimPosts(), postId);
        assertEquals(String.valueOf(board), claimed.get("boardId").asText());
        assertEquals("6001", claimed.get("messageId").asText());
        assertTrue(claimed.get("boardExists").asBoolean());
        JsonNode snapshot = internal("GET", "/api/internal/board-posts/snapshot?serverId=" + w.serverId + "&boardId=" + board,
                null).expect(200).json();
        assertEquals("Posted", snapshot.get("board").get("name").asText());
        int asUser = callRaw("GET", "/api/internal/board-posts/snapshot?serverId=" + w.serverId + "&boardId=" + board,
                "Bearer " + accessToken(OWNER), null).status();
        assertTrue(asUser == 400 || asUser == 403, "only the bot may read whole boards, not even the owner: " + asUser);
        reportPosts(List.of(postId), List.of(), List.of());
        assertTrue(mine(claimPosts(), postId).isEmpty(), "an up-to-date post is left alone");

        // A change marks it; it waits a moment for more changes, then is redrawn once.
        w.createTask(OWNER, board, todo, "First");
        w.createTask(OWNER, board, todo, "Second");
        assertTrue(mine(claimPosts(), postId).isEmpty(), "a burst of changes settles first");
        settle(postId);
        only(claimPosts(), postId);

        // A change while it is being redrawn is not lost: it is redrawn again once reported.
        w.createTask(OWNER, board, todo, "Third");
        settle(postId);
        assertTrue(mine(claimPosts(), postId).isEmpty(), "not claimed twice at once");
        reportPosts(List.of(postId), List.of(), List.of());
        settle(postId);
        only(claimPosts(), postId);

        // A failed redraw is retried later, and given up on after a day.
        reportPosts(List.of(), List.of(), List.of(postId));
        assertTrue(mine(claimPosts(), postId).isEmpty(), "retried later, not at once");
        jdbcTemplate.update("UPDATE board_posts SET retry_after = now() - interval '1 second' WHERE post_id = ?", postId);
        only(claimPosts(), postId);
        jdbcTemplate.update("UPDATE board_posts SET failing_since = now() - interval '25 hours' WHERE post_id = ?", postId);
        reportPosts(List.of(), List.of(), List.of(postId));
        assertEquals(0, count("SELECT count(*) FROM board_posts WHERE post_id = " + postId));

        // Deleting the board leaves its posts, marked, so the bot can say so before they go.
        long second = asBot("POST", posts, BOT_TOKEN, OWNER, w.serverId,
                Map.of("channelId", "5001", "messageId", "6002")).expect(201).json().get("postId").asLong();
        only(claimPosts(), second);
        reportPosts(List.of(second), List.of(), List.of());
        call("DELETE", w.path("/boards/" + board), OWNER, null).expect(204);
        settle(second);
        assertFalse(only(claimPosts(), second).get("boardExists").asBoolean());
        reportPosts(List.of(), List.of(second), List.of());
        assertEquals(0, count("SELECT count(*) FROM board_posts WHERE post_id = " + second));

        // At most ten posts per board.
        long busy = w.createBoard(OWNER, "Busy");
        for (int i = 0; i < 10; i++) {
            asBot("POST", w.boardPath(busy, "/posts"), BOT_TOKEN, OWNER, w.serverId,
                    Map.of("channelId", "5001", "messageId", String.valueOf(7000 + i))).expect(201);
        }
        assertEquals(400, asBot("POST", w.boardPath(busy, "/posts"), BOT_TOKEN, OWNER, w.serverId,
                Map.of("channelId", "5001", "messageId", "7999")).status());
        claimPosts();
    }

    @Test
    void boardPosts_audience_namesThoseWhoCouldNotSeeTheBoard() throws Exception {
        World w = bootstrapServer();
        long board = w.createBoard(OWNER, "Staff only");
        w.createRule(OWNER, "BOARD", board, "DISCORD_PERMISSION", VIEW_CHANNEL, "VIEW_BOARD", "DENY").expect(201);
        w.createRule(OWNER, "BOARD", board, "ROLE", w.modsRole, "VIEW_BOARD", "ALLOW").expect(201);
        String audience = w.boardPath(board, "/posts/audience");
        List<String> everyone = List.of(String.valueOf(OWNER), String.valueOf(MOD), String.valueOf(MEMBER),
                String.valueOf(OTHER), "9998", String.valueOf(MEMBER));

        JsonNode answer = asBot("POST", audience, BOT_TOKEN, OWNER, w.serverId, Map.of("userIds", everyone))
                .expect(200).json();
        assertEquals(5, answer.get("checked").asInt(), "each person counts once");
        assertEquals(3, answer.get("hidden").asInt());
        assertEquals(List.of(String.valueOf(MEMBER), String.valueOf(OTHER), "9998"), texts(answer.get("hiddenUserIds")),
                "members outside the mods, and someone not in the server; the owner and mods can see it");
        for (long user : List.of(OWNER, MOD, MEMBER, OTHER)) {
            boolean sees = call("GET", w.path("/boards/" + board), user, null).status() == 200;
            assertEquals(!sees, texts(answer.get("hiddenUserIds")).contains(String.valueOf(user)),
                    "the same answer as asking about each person: " + user);
        }

        long open = w.createBoard(OWNER, "Open");
        assertEquals(0, asBot("POST", w.boardPath(open, "/posts/audience"), BOT_TOKEN, OWNER, w.serverId,
                Map.of("userIds", List.of(String.valueOf(MOD), String.valueOf(MEMBER)))).expect(200).json()
                .get("hidden").asInt());
        assertEquals(403, asBot("POST", audience, BOT_TOKEN, MEMBER, w.serverId, Map.of("userIds", everyone)).status(),
                "only those who may post the board may ask");
    }

    private Response internal(String method, String path, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Internal-Bot-Token", BOT_TOKEN)
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = response.body().isBlank() ? null : objectMapper.readTree(response.body());
        return new Response(response.statusCode(), json, response.body());
    }

    private JsonNode claimPosts() throws Exception {
        return internal("POST", "/api/internal/board-posts/claim?limit=50", null).expect(200).json();
    }

    private void reportPosts(List<Long> done, List<Long> gone, List<Long> retry) throws Exception {
        internal("POST", "/api/internal/board-posts/report", Map.of(
                "done", done.stream().map(String::valueOf).toList(),
                "gone", gone.stream().map(String::valueOf).toList(),
                "retry", retry.stream().map(String::valueOf).toList())).expect(204);
    }

    /** As if the post's change happened long enough ago to be redrawn. */
    private void settle(long postId) {
        jdbcTemplate.update("UPDATE board_posts SET dirty_at = now() - interval '10 seconds' "
                + "WHERE post_id = ? AND dirty_at IS NOT NULL", postId);
    }

    private static List<JsonNode> mine(JsonNode claimed, long postId) {
        List<JsonNode> found = new ArrayList<>();
        claimed.forEach(post -> {
            if (post.get("postId").asLong() == postId) {
                found.add(post);
            }
        });
        return found;
    }

    private static JsonNode only(JsonNode claimed, long postId) {
        List<JsonNode> found = mine(claimed, postId);
        assertEquals(1, found.size(), "post " + postId + " in " + claimed);
        return found.get(0);
    }

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
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
