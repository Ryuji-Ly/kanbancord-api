package com.kanbancord_api.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanbancord_api.integration.config.TestcontainersConfiguration;
import com.kanbancord_api.security.JwtTokenService;
import com.kanbancord_api.server.MemberCleanup;
import com.kanbancord_api.session.UserSessionService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lazy member storage, with a stand-in for Discord: only the owner is stored when the bot syncs a server; others
 * are looked up in Discord when they first use KanbanCord or are picked, and the daily cleanup removes members
 * nothing needs and users who are in no server and made nothing.
 */
@Tag("integration")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LazyMembersIntegrationTest {

    private static final String BOT_TOKEN = randomSecret();
    private static final String JWT_SECRET = randomSecret();
    private static final String DISCORD_BOT_TOKEN = "fake-discord-bot-token";

    private static final long SERVER = 800_000_000_000L;
    private static final long MEMBERS_ROLE = SERVER + 1;
    private static final long MODS_ROLE = SERVER + 2;
    private static final long VIEW_CHANNEL = 1L << 10;
    private static final long SEND_MESSAGES = 1L << 11;
    private static final long MANAGE_MESSAGES = 1L << 13;

    private static final long OWNER = 2_000L;
    private static final long MOD = 2_001L;
    private static final long MEMBER = 2_002L;
    private static final long OTHER = 2_003L;
    private static final long NEWBIE = 2_004L;
    private static final long STRANGER = 2_005L;

    private static final FakeDiscord DISCORD = new FakeDiscord();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("kanbancord.auth.jwt.secret", () -> JWT_SECRET);
        registry.add("kanbancord.internal-sync.bot-token", () -> sha256Hex(BOT_TOKEN));
        registry.add("kanbancord.discord.api-base-url", DISCORD::baseUrl);
        registry.add("kanbancord.discord.bot-token", () -> DISCORD_BOT_TOKEN);
        registry.add("kanbancord.members.cleanup-first-ms", () -> String.valueOf(24L * 60 * 60 * 1000));
        registry.add("kanbancord.notifications.reminder-first-check-ms", () -> String.valueOf(24L * 60 * 60 * 1000));
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JwtTokenService jwtTokenService;
    @Autowired
    private UserSessionService userSessionService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MemberCleanup memberCleanup;

    private final Map<Long, String> accessTokens = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void membersAreStoredWhenUsedOrPicked_andCleanedUpWhenNothingNeedsThem() throws Exception {
        DISCORD.add(SERVER, MOD, "mod", List.of(MEMBERS_ROLE, MODS_ROLE));
        DISCORD.add(SERVER, MEMBER, "member", List.of(MEMBERS_ROLE));
        DISCORD.add(SERVER, OTHER, "otherperson", List.of(MEMBERS_ROLE));
        DISCORD.add(SERVER, NEWBIE, "newbie", List.of());

        assertEquals(204, bootstrap());
        assertEquals(List.of(OWNER), storedMembers(), "only the owner is stored when the bot syncs");
        assertEquals(0, count("SELECT count(*) FROM users WHERE user_id IN (?, ?, ?)", MOD, MEMBER, OTHER),
                "people who never used KanbanCord are not stored as users either");

        call("PUT", path("/features"), OWNER, Map.of("ASSIGNEES", true)).expect(200);
        long board = call("POST", path("/boards"), OWNER, Map.of("name", "Sprint", "serverId", SERVER,
                "columnNames", List.of("Todo", "Doing"))).expect(201).json().get("boardId").asLong();

        // First use: looked up in Discord, as the bot, and stored with their roles.
        int asked = DISCORD.requests.size();
        call("GET", path("/boards/" + board), MEMBER, null).expect(200);
        assertEquals(asked + 1, DISCORD.requests.size());
        assertEquals("Bot " + DISCORD_BOT_TOKEN, DISCORD.lastAuthorization);
        assertTrue(storedMembers().contains(MEMBER));
        assertEquals(1, count("""
                SELECT count(*) FROM member_roles mr JOIN server_members sm ON sm.id = mr.server_member_id
                WHERE sm.server_id = ? AND sm.user_id = ? AND mr.role_id = ?""", SERVER, MEMBER, MEMBERS_ROLE));
        assertEquals(1, count("SELECT count(*) FROM server_members WHERE server_id = ? AND user_id = ? "
                + "AND last_used_at IS NOT NULL", SERVER, MEMBER), "using KanbanCord is recorded");
        call("GET", path("/boards/" + board), MEMBER, null).expect(200);
        assertEquals(asked + 1, DISCORD.requests.size(), "once stored, Discord is not asked again");

        // Roles from Discord count: mods can assign others.
        long todo = column(board, "Todo");
        long task = call("POST", path("/boards/" + board + "/tasks"), MEMBER,
                Map.of("title", "Write docs", "boardId", board, "columnId", todo)).expect(201).json().get("taskId").asLong();

        // Someone not in the server is refused, and the answer is remembered for a while.
        assertEquals(403, call("GET", path("/boards/" + board), STRANGER, null).status());
        int afterStranger = DISCORD.requests.size();
        assertEquals(403, call("GET", path("/boards/" + board), STRANGER, null).status());
        assertEquals(afterStranger, DISCORD.requests.size());
        assertFalse(storedMembers().contains(STRANGER));

        // Search finds stored members and, through Discord, the others, without storing them.
        JsonNode found = call("GET", path("/members/search?q=oth"), OWNER, null).expect(200).json();
        JsonNode other = find(found, OTHER);
        assertNotNull(other, found.toString());
        assertTrue(other.get("id").isNull(), "not stored yet");
        assertFalse(storedMembers().contains(OTHER));
        assertNotNull(find(call("GET", path("/members/search?q=mem"), OWNER, null).expect(200).json(), MEMBER),
                "stored members are found by name");

        // Picking them (assigning) stores them.
        String assignments = path("/boards/" + board + "/tasks/" + task + "/assignments");
        long assignment = call("POST", assignments, MOD, Map.of("taskId", task, "userId", OTHER))
                .expect(201).json().get("id").asLong();
        assertTrue(storedMembers().contains(OTHER));
        assertEquals(400, call("POST", assignments, MOD, Map.of("taskId", task, "userId", STRANGER)).status(),
                "people outside the server cannot be assigned");

        // The bot's updates only touch stored members.
        assertEquals(204, sync("PUT", "/api/internal/sync/servers/" + SERVER + "/members/" + NEWBIE,
                Map.of("username", "newbie", "roleIds", List.of())));
        assertEquals(204, sync("PUT", "/api/internal/sync/servers/" + SERVER + "/members/" + NEWBIE + "/roles",
                Map.of("roleIds", List.of(MEMBERS_ROLE))));
        assertEquals(204, sync("PUT", "/api/internal/sync/servers/" + SERVER + "/members/" + MEMBER,
                Map.of("username", "member", "nickname", "Mem", "roleIds", List.of(MEMBERS_ROLE, MODS_ROLE))));
        assertFalse(storedMembers().contains(NEWBIE));
        assertEquals("Mem", jdbcTemplate.queryForObject(
                "SELECT nickname FROM server_members WHERE server_id = ? AND user_id = ?", String.class, SERVER, MEMBER));

        // A full resync keeps the stored ones and adds nobody.
        List<Long> before = storedMembers();
        assertEquals(204, bootstrap());
        assertEquals(before, storedMembers());
        assertEquals(1, count("""
                SELECT count(*) FROM member_roles mr JOIN server_members sm ON sm.id = mr.server_member_id
                WHERE sm.server_id = ? AND sm.user_id = ? AND mr.role_id = ?""", SERVER, MEMBER, MEMBERS_ROLE),
                "roles are what the bot sent");

        // Cleanup: an assigned member stays; once unassigned, nothing needs them.
        memberCleanup.removeUnusedMembers(Duration.ZERO);
        assertTrue(storedMembers().containsAll(List.of(OWNER, MEMBER, OTHER)));
        call("DELETE", assignments + "/" + assignment, MOD, null).expect(204);
        memberCleanup.removeUnusedMembers(Duration.ZERO);
        assertFalse(storedMembers().contains(OTHER));
        assertTrue(storedMembers().containsAll(List.of(OWNER, MEMBER, MOD)), "people who used it stay");

        // Orphaned users: OTHER is in no stored server and made nothing, so goes. MEMBER leaves the server but
        // made a task, so stays (their name is on it).
        assertEquals(204, sync("DELETE", "/api/internal/sync/servers/" + SERVER + "/members/" + MEMBER, null));
        jdbcTemplate.update("DELETE FROM user_sessions WHERE user_id = ?", MEMBER);
        memberCleanup.removeOrphanedUsers(Duration.ZERO);
        assertEquals(0, count("SELECT count(*) FROM users WHERE user_id = ?", OTHER));
        assertEquals(1, count("SELECT count(*) FROM users WHERE user_id = ?", MEMBER));
        assertEquals(1, count("SELECT count(*) FROM users WHERE user_id = ?", OWNER));
        assertEquals(1, count("SELECT count(*) FROM tasks WHERE task_id = ?", task));
    }

    private int bootstrap() throws Exception {
        List<Map<String, Object>> roles = List.of(
                Map.of("roleId", MEMBERS_ROLE, "name", "Members", "position", 1,
                        "discordPermissions", VIEW_CHANNEL | SEND_MESSAGES),
                Map.of("roleId", MODS_ROLE, "name", "Mods", "position", 2,
                        "discordPermissions", VIEW_CHANNEL | SEND_MESSAGES | MANAGE_MESSAGES));
        List<Map<String, Object>> members = new ArrayList<>(List.of(
                Map.of("userId", OWNER, "username", "owner", "roleIds", List.of()),
                Map.of("userId", MOD, "username", "mod", "roleIds", List.of(MEMBERS_ROLE, MODS_ROLE)),
                Map.of("userId", MEMBER, "username", "member", "roleIds", List.of(MEMBERS_ROLE)),
                Map.of("userId", OTHER, "username", "otherperson", "roleIds", List.of(MEMBERS_ROLE)),
                Map.of("userId", NEWBIE, "username", "newbie", "roleIds", List.of())));
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Lazy " + SERVER);
        body.put("ownerId", OWNER);
        body.put("ownerUsername", "owner");
        body.put("roles", roles);
        body.put("members", members);
        return sync("POST", "/api/internal/sync/servers/" + SERVER + "/bootstrap", body);
    }

    private List<Long> storedMembers() {
        return jdbcTemplate.queryForList("SELECT user_id FROM server_members WHERE server_id = ? ORDER BY user_id",
                Long.class, SERVER);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private long column(long board, String name) throws Exception {
        for (JsonNode column : call("GET", path("/boards/" + board + "/columns"), OWNER, null).expect(200).json()) {
            if (name.equals(column.get("name").asText())) {
                return column.get("columnId").asLong();
            }
        }
        throw new AssertionError("No column " + name);
    }

    private static JsonNode find(JsonNode members, long userId) {
        for (JsonNode member : members) {
            if (String.valueOf(userId).equals(member.get("userId").asText())) {
                return member;
            }
        }
        return null;
    }

    private static String path(String suffix) {
        return "/api/servers/" + SERVER + suffix;
    }

    private int sync(String method, String path, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Internal-Bot-Token", BOT_TOKEN)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private Response call(String method, String path, long userId, Object body) throws Exception {
        String token = accessTokens.computeIfAbsent(userId, id -> {
            jdbcTemplate.update("INSERT INTO users (user_id, username) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    id, "user" + id);
            return jwtTokenService.issueToken(id, userSessionService.start(id, "e2e").session().getSessionId());
        });
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = response.body().isBlank() ? null : objectMapper.readTree(response.body());
        return new Response(response.statusCode(), json, response.body());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    record Response(int status, JsonNode json, String body) {
        Response expect(int expected) {
            assertEquals(expected, status, body);
            return this;
        }
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

    /** Answers Discord's member and member search endpoints from a list of members per server. */
    static final class FakeDiscord {

        private final HttpServer server;
        private final Map<Long, Map<Long, String>> members = new ConcurrentHashMap<>();
        final List<String> requests = new CopyOnWriteArrayList<>();
        volatile String lastAuthorization;

        FakeDiscord() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            server.createContext("/guilds/", exchange -> {
                requests.add(exchange.getRequestURI().toString());
                lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                String[] parts = exchange.getRequestURI().getPath().split("/");
                long guild = Long.parseLong(parts[2]);
                Map<Long, String> inGuild = members.getOrDefault(guild, Map.of());
                String response;
                int status = 200;
                if ("search".equals(parts[4])) {
                    String query = "";
                    for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
                        if (pair.startsWith("query=")) {
                            query = URLDecoder.decode(pair.substring(6), StandardCharsets.UTF_8);
                        }
                    }
                    String prefix = query;
                    response = "[" + String.join(",", inGuild.values().stream()
                            .filter(json -> json.contains("\"username\":\"" + prefix)).toList()) + "]";
                } else {
                    response = inGuild.get(Long.parseLong(parts[4]));
                    if (response == null) {
                        status = 404;
                        response = "{\"message\":\"Unknown Member\",\"code\":10007}";
                    }
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        void add(long guild, long userId, String username, List<Long> roles) {
            String roleList = String.join(",", roles.stream().map(id -> "\"" + id + "\"").toList());
            members.computeIfAbsent(guild, id -> new ConcurrentHashMap<>()).put(userId, "{\"user\":{\"id\":\"" + userId
                    + "\",\"username\":\"" + username + "\",\"global_name\":null,\"avatar\":null},\"nick\":null,"
                    + "\"roles\":[" + roleList + "],\"joined_at\":\"2026-10-01T12:00:00.000000+00:00\"}");
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }
    }
}
