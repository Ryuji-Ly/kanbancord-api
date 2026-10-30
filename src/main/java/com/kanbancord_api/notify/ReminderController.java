package com.kanbancord_api.notify;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.security.CurrentUser;
import com.kanbancord_api.event.UserEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reminder times before a due date, in minutes: the server's (changed by its managers), a board's own (changed by
 * whoever edits the board), and each person's own, everywhere or for one board. A list replaces the times; null
 * (for a board or a person) goes back to what applies above it.
 */
@RestController
public class ReminderController {

    private final ReminderTimes times;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbcTemplate;

    public ReminderController(ReminderTimes times, Authorizer authorizer, ResourceValidator resourceValidator,
            ApplicationEventPublisher events, JdbcTemplate jdbcTemplate) {
        this.times = times;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.events = events;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@code minutes}: the times set here, or null where none are; {@code effective}: what applies. */
    public record Times(List<Integer> minutes, List<Integer> effective) {
    }

    public record Change(List<Integer> minutes) {
    }

    /** A person's own times for one board, with where it is, to list them. */
    public record BoardTimes(Long boardId, String boardName, String serverId, String serverName, List<Integer> minutes) {
    }

    /** {@code everywhere}: the person's own times for every board, or null; {@code boards}: their own per board. */
    public record Mine(List<Integer> everywhere, List<BoardTimes> boards) {
    }

    // ── Server ───────────────────────────────────────────────────────────────

    @GetMapping("/api/servers/{serverId}/reminders")
    public ResponseEntity<Times> server(@PathVariable Long serverId, @CurrentUser Long userId) {
        authorizer.requireServerPermission(userId, serverId, "VIEW_SERVER");
        List<Integer> minutes = times.server(serverId);
        return ResponseEntity.ok(new Times(minutes, minutes));
    }

    @PutMapping("/api/servers/{serverId}/reminders")
    @Transactional
    public ResponseEntity<Times> setServer(@PathVariable Long serverId, @CurrentUser Long userId,
            @RequestBody Change change) {
        authorizer.requireServerPermission(userId, serverId, "MANAGE_SERVER_PERMISSIONS");
        List<Integer> before = times.server(serverId);
        List<Integer> after = ReminderTimes.normalise(change.minutes());
        if (!after.equals(before)) {
            times.setServer(serverId, after);
            events.publishEvent(DomainEvent.changed(EventType.NOTIFICATIONS_UPDATED, serverId, null, serverId, userId,
                    Map.of("reminders", before), Map.of("reminders", after)));
        }
        return ResponseEntity.ok(new Times(after, after));
    }

    // ── Board ────────────────────────────────────────────────────────────────

    @GetMapping("/api/servers/{serverId}/boards/{boardId}/reminders")
    public ResponseEntity<Times> board(@PathVariable Long serverId, @PathVariable Long boardId,
            @CurrentUser Long userId) {
        resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_BOARD");
        return ResponseEntity.ok(new Times(times.board(boardId).orElse(null), times.shared(serverId, boardId)));
    }

    @PutMapping("/api/servers/{serverId}/boards/{boardId}/reminders")
    @Transactional
    public ResponseEntity<Times> setBoard(@PathVariable Long serverId, @PathVariable Long boardId,
            @CurrentUser Long userId, @RequestBody Change change) {
        resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(userId, serverId, boardId, "EDIT_BOARD_DETAILS");
        List<Integer> before = times.board(boardId).orElse(null);
        List<Integer> after = change.minutes() == null ? null : ReminderTimes.normalise(change.minutes());
        if (!Objects.equals(before, after)) {
            times.setBoard(boardId, after);
            events.publishEvent(DomainEvent.changed(EventType.NOTIFICATIONS_UPDATED, serverId, boardId, boardId, userId,
                    Map.of("reminders", before == null ? "server" : before),
                    Map.of("reminders", after == null ? "server" : after)));
        }
        return ResponseEntity.ok(new Times(after, times.shared(serverId, boardId)));
    }

    // ── A person's own ───────────────────────────────────────────────────────

    /** The caller's own times for one board, falling back to their own everywhere, then the board's. */
    @GetMapping("/api/servers/{serverId}/boards/{boardId}/reminders/mine")
    public ResponseEntity<Times> mineForBoard(@PathVariable Long serverId, @PathVariable Long boardId,
            @CurrentUser Long userId) {
        resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_BOARD");
        return ResponseEntity.ok(mine(serverId, boardId, userId));
    }

    @PutMapping("/api/servers/{serverId}/boards/{boardId}/reminders/mine")
    @Transactional
    public ResponseEntity<Times> setMineForBoard(@PathVariable Long serverId, @PathVariable Long boardId,
            @CurrentUser Long userId, @RequestBody Change change) {
        resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_BOARD");
        times.setPersonal(userId, boardId, change.minutes() == null ? null : ReminderTimes.normalise(change.minutes()));
        events.publishEvent(new UserEvent(UserEvent.Type.NOTIFICATION_SETTINGS_CHANGED, userId, Map.of()));
        return ResponseEntity.ok(mine(serverId, boardId, userId));
    }

    @GetMapping("/api/me/reminders")
    public ResponseEntity<Mine> mine(@CurrentUser Long userId) {
        return ResponseEntity.ok(mineEverywhere(userId));
    }

    /** The caller's own times for every board; null removes them. */
    @PutMapping("/api/me/reminders")
    @Transactional
    public ResponseEntity<Mine> setMine(@CurrentUser Long userId, @RequestBody Change change) {
        authorizer.requireAuthenticatedUserId();
        times.setPersonal(userId, null, change.minutes() == null ? null : ReminderTimes.normalise(change.minutes()));
        events.publishEvent(new UserEvent(UserEvent.Type.NOTIFICATION_SETTINGS_CHANGED, userId, Map.of()));
        return ResponseEntity.ok(mineEverywhere(userId));
    }

    private Times mine(Long serverId, Long boardId, Long userId) {
        List<Integer> forBoard = times.personal(userId).stream()
                .filter(own -> boardId.equals(own.boardId())).map(ReminderTimes.Personal::minutes)
                .findFirst().orElse(null);
        return new Times(forBoard, times.personalFor(userId, boardId).orElseGet(() -> times.shared(serverId, boardId)));
    }

    private Mine mineEverywhere(Long userId) {
        List<Integer> everywhere = null;
        List<BoardTimes> boards = new ArrayList<>();
        for (ReminderTimes.Personal own : times.personal(userId)) {
            if (own.boardId() == null) {
                everywhere = own.minutes();
                continue;
            }
            // Only boards the person can still see are listed; the others' times stay, unused.
            List<BoardTimes> found = jdbcTemplate.query("""
                    SELECT b.board_id, b.name, s.server_id, s.name AS server_name FROM boards b
                    JOIN servers s ON s.server_id = b.server_id WHERE b.board_id = ?
                    """, (rs, i) -> new BoardTimes(rs.getLong("board_id"), rs.getString("name"),
                    String.valueOf(rs.getLong("server_id")), rs.getString("server_name"), own.minutes()), own.boardId());
            for (BoardTimes board : found) {
                try {
                    authorizer.requireBoardPermission(userId, Long.valueOf(board.serverId()), board.boardId(), "VIEW_BOARD");
                    boards.add(board);
                } catch (RuntimeException hidden) {
                    // No longer visible to them.
                }
            }
        }
        return new Mine(everywhere, boards);
    }
}
