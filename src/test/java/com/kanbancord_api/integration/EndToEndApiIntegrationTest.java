package com.kanbancord_api.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanbancord_api.integration.config.TestcontainersConfiguration;
import com.kanbancord_api.security.JwtTokenService;
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

import java.lang.reflect.Type;
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
import java.util.concurrent.BlockingQueue;
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

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("kanbancord.auth.jwt.secret", () -> JWT_SECRET);
        registry.add("kanbancord.internal-sync.bot-token", () -> sha256Hex(BOT_TOKEN));
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JwtTokenService jwtTokenService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

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

        JsonNode column = call("POST", w.boardPath(board, "/columns/" + doing + "/move"), MOD, Map.of("index", 0))
                .expect(200).json();
        assertEquals(1, column.get("position").asInt());
        assertEquals(List.of("Doing", "Todo"), w.snapshot(MOD, board).get("columns").findValuesAsText("name"));

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

        List<JsonNode> log = auditLog(w);
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
        List<JsonNode> afterDelete = auditLog(w);
        assertEquals(5, afterDelete.size());
        JsonNode deleted = afterDelete.get(4);
        assertEquals("BOARD_DELETED", deleted.get("action").asText());
        assertEquals(board, deleted.at("/changes/deleted/boardId").asLong());
        assertTrue(afterDelete.stream().allMatch(e -> e.get("boardId").isNull()), afterDelete.toString());
    }

    /** The server's audit log, oldest first. */
    private List<JsonNode> auditLog(World w) throws Exception {
        List<JsonNode> entries = new ArrayList<>();
        call("GET", w.path("/audit-logs"), OWNER, null).expect(200).json().forEach(entries::add);
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

    // ── Helpers ─────────────────────────────────────────────────────────────

    private World bootstrapServer() throws Exception {
        return bootstrapServer(false);
    }

    /**
     * @param withEveryone also sync an @everyone role (id = server id) granting View Channels, and
     *                     NEWBIE, a member with no roles
     */
    private World bootstrapServer(boolean withEveryone) throws Exception {
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
        return callRaw(method, path, userId == null ? null : "Bearer " + jwtTokenService.issueToken(userId), body);
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
            JsonNode ticket = test.call("POST", "/api/realtime/tickets", userId, null).expect(200).json();
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
