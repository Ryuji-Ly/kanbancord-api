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
import java.util.concurrent.CompletableFuture;
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
        assertEquals("TASK_CREATED", memberOnPublic.events.get(10, TimeUnit.SECONDS).get("eventType"));
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
        Map<?, ?> event = mod.events.get(10, TimeUnit.SECONDS);
        assertEquals("TASK_CREATED", event.get("eventType"));
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
        long serverId = SERVER_IDS.addAndGet(1_000);
        World w = new World(serverId, serverId + 1, serverId + 2);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "E2E " + serverId);
        body.put("ownerId", OWNER);
        body.put("ownerUsername", "owner");
        body.put("roles", List.of(
                Map.of("roleId", w.membersRole, "name", "Members", "position", 1,
                        "discordPermissions", VIEW_CHANNEL | SEND_MESSAGES),
                Map.of("roleId", w.modsRole, "name", "Mods", "position", 2,
                        "discordPermissions", VIEW_CHANNEL | SEND_MESSAGES | MANAGE_MESSAGES)));
        body.put("members", List.of(
                Map.of("userId", OWNER, "username", "owner", "roleIds", List.of()),
                Map.of("userId", MOD, "username", "mod", "roleIds", List.of(w.membersRole, w.modsRole)),
                Map.of("userId", MEMBER, "username", "member", "roleIds", List.of(w.membersRole)),
                Map.of("userId", OTHER, "username", "other", "roleIds", List.of(w.membersRole))));

        HttpRequest request = HttpRequest.newBuilder(uri("/api/internal/sync/servers/" + serverId + "/bootstrap"))
                .header("Content-Type", "application/json")
                .header("X-Internal-Bot-Token", BOT_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        assertEquals(204, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());

        for (JsonNode entry : call("GET", w.path("/permissions/catalog"), OWNER, null).expect(200).json()) {
            w.catalog.put(entry.get("key").asText(), entry.get("permissionId").asLong());
        }
        return w;
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
        final CompletableFuture<Map<?, ?>> events = new CompletableFuture<>();
        final CompletableFuture<String> failure = new CompletableFuture<>();
        private StompSession session;

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
                    events.complete((Map<?, ?>) payload);
                }
            });
        }
    }
}
