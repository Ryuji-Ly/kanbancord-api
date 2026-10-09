package com.kanbancord_api.stats;

import com.kanbancord_api.access.Authorizer;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Totals for the bot's status line. Only the bot, with its internal token, may call this. Servers the
 * bot has left are not counted.
 */
@RestController
public class InternalStatsController {

    private static final String BOT_TOKEN_HEADER = "X-Internal-Bot-Token";

    private final JdbcTemplate jdbcTemplate;
    private final Authorizer authorizer;

    public InternalStatsController(JdbcTemplate jdbcTemplate, Authorizer authorizer) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizer = authorizer;
    }

    public record BotStats(long boards, long tasks) {
    }

    @GetMapping("/api/internal/stats")
    public ResponseEntity<BotStats> stats(@RequestHeader(BOT_TOKEN_HEADER) String botToken) {
        authorizer.requireInternalSyncAccess(botToken);
        BotStats stats = jdbcTemplate.queryForObject("""
                SELECT
                  (SELECT count(*) FROM boards b JOIN servers s ON s.server_id = b.server_id
                    WHERE s.bot_present) AS boards,
                  (SELECT count(*) FROM tasks t JOIN boards b ON b.board_id = t.board_id
                    JOIN servers s ON s.server_id = b.server_id WHERE s.bot_present) AS tasks
                """, (row, index) -> new BotStats(row.getLong("boards"), row.getLong("tasks")));
        return ResponseEntity.ok(stats);
    }
}
