package com.kanbancord_api.notify;

import com.kanbancord_api.exception.BadRequestException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * When people are reminded before a task is due, in minutes before the due date. The server sets times for every
 * board, a board can have its own, and each person can choose their own, everywhere or for one board: the most
 * specific wins (the person's for the board, the person's everywhere, the board's, the server's). Feeds follow the
 * server's and board's times; a person's own times only change their own direct messages.
 */
@Service
public class ReminderTimes {

    /** What a server starts with: a day before, as before reminder times could be chosen. */
    public static final List<Integer> DEFAULT = List.of(1440);
    /** Reminders a list may hold. */
    public static final int MAX_TIMES = 5;
    public static final int MIN_MINUTES = 5;
    /** Thirty days. */
    public static final int MAX_MINUTES = 43_200;

    private final JdbcTemplate jdbcTemplate;

    public ReminderTimes(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** A person's own times: everywhere ({@code boardId} null), or for one board. */
    public record Personal(Long boardId, List<Integer> minutes) {
    }

    /** The times, checked: at most five, each between five minutes and thirty days, latest first, no repeats. */
    public static List<Integer> normalise(Collection<?> values) {
        if (values == null) {
            throw new BadRequestException("Give the reminder times as a list of minutes before the due date");
        }
        TreeSet<Integer> minutes = new TreeSet<>(Comparator.reverseOrder());
        for (Object value : values) {
            if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
                throw new BadRequestException("Reminder times are whole minutes before the due date");
            }
            int minute = number.intValue();
            if (minute < MIN_MINUTES || minute > MAX_MINUTES) {
                throw new BadRequestException("Reminders can be from 5 minutes to 30 days before the due date");
            }
            minutes.add(minute);
        }
        if (minutes.size() > MAX_TIMES) {
            throw new BadRequestException("At most " + MAX_TIMES + " reminders before the due date");
        }
        return List.copyOf(minutes);
    }

    // ── Server and board ─────────────────────────────────────────────────────

    public List<Integer> server(Long serverId) {
        List<List<Integer>> rows = jdbcTemplate.query("SELECT reminder_minutes FROM servers WHERE server_id = ?",
                (rs, i) -> minutes(rs.getArray(1)), serverId);
        return rows.isEmpty() || rows.get(0) == null ? DEFAULT : rows.get(0);
    }

    public void setServer(Long serverId, List<Integer> minutes) {
        jdbcTemplate.update("UPDATE servers SET reminder_minutes = ? WHERE server_id = ?", (Object) array(minutes), serverId);
    }

    /** The board's own times, if it has any. */
    public Optional<List<Integer>> board(Long boardId) {
        List<List<Integer>> rows = jdbcTemplate.query("SELECT reminder_minutes FROM boards WHERE board_id = ?",
                (rs, i) -> minutes(rs.getArray(1)), boardId);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /** Null goes back to the server's times. */
    public void setBoard(Long boardId, List<Integer> minutes) {
        jdbcTemplate.update("UPDATE boards SET reminder_minutes = ? WHERE board_id = ?",
                minutes == null ? null : array(minutes), boardId);
    }

    /** What a board's feeds and people without times of their own go by. */
    public List<Integer> shared(Long serverId, Long boardId) {
        return board(boardId).orElseGet(() -> server(serverId));
    }

    // ── A person's own ───────────────────────────────────────────────────────

    /** Everything the person chose: everywhere first, then by board. */
    public List<Personal> personal(Long userId) {
        return jdbcTemplate.query("""
                SELECT board_id, minutes FROM user_reminder_times WHERE user_id = ?
                ORDER BY board_id NULLS FIRST
                """, (rs, i) -> new Personal((Long) rs.getObject("board_id"), minutes(rs.getArray("minutes"))), userId);
    }

    /** The person's own times for the board, if they chose any: for the board itself, or everywhere. */
    public Optional<List<Integer>> personalFor(Long userId, Long boardId) {
        Personal best = null;
        for (Personal own : personal(userId)) {
            if (own.boardId() == null && best == null) {
                best = own;
            } else if (boardId.equals(own.boardId())) {
                best = own;
            }
        }
        return Optional.ofNullable(best).map(Personal::minutes);
    }

    /** Null removes the person's own times there (everywhere when {@code boardId} is null). */
    public void setPersonal(Long userId, Long boardId, List<Integer> minutes) {
        jdbcTemplate.update("DELETE FROM user_reminder_times WHERE user_id = ? AND board_id IS NOT DISTINCT FROM ?",
                userId, boardId);
        if (minutes != null) {
            jdbcTemplate.update("INSERT INTO user_reminder_times (user_id, board_id, minutes) VALUES (?, ?, ?)",
                    userId, boardId, array(minutes));
        }
    }

    /**
     * Everyone with times of their own that apply to these boards, by board: those who chose times for the board,
     * and those who chose times everywhere.
     *
     * @return by board id, by user id, their times there
     */
    public Map<Long, Map<Long, List<Integer>>> personalByBoard(Collection<Long> boardIds) {
        Map<Long, Map<Long, List<Integer>>> result = new LinkedHashMap<>();
        if (boardIds.isEmpty()) {
            return result;
        }
        Map<Long, List<Integer>> everywhere = new LinkedHashMap<>();
        Map<Long, Map<Long, List<Integer>>> own = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT user_id, board_id, minutes FROM user_reminder_times
                WHERE board_id IS NULL OR board_id = ANY (?)
                """, rs -> {
            Long boardId = (Long) rs.getObject("board_id");
            List<Integer> minutes = minutes(rs.getArray("minutes"));
            if (boardId == null) {
                everywhere.put(rs.getLong("user_id"), minutes);
            } else {
                own.computeIfAbsent(boardId, id -> new LinkedHashMap<>()).put(rs.getLong("user_id"), minutes);
            }
        }, (Object) boardIds.toArray(Long[]::new));
        for (Long boardId : boardIds) {
            Map<Long, List<Integer>> people = new LinkedHashMap<>(everywhere);
            people.putAll(own.getOrDefault(boardId, Map.of()));
            if (!people.isEmpty()) {
                result.put(boardId, people);
            }
        }
        return result;
    }

    /** The longest time anyone reminds before a due date, so the scheduler knows how far ahead to look. */
    public int longest() {
        Integer longest = jdbcTemplate.queryForObject("""
                SELECT GREATEST(
                    (SELECT max(m) FROM servers, unnest(reminder_minutes) m),
                    (SELECT max(m) FROM boards, unnest(reminder_minutes) m),
                    (SELECT max(m) FROM user_reminder_times, unnest(minutes) m))
                """, Integer.class);
        return longest == null ? 0 : longest;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static List<Integer> minutes(Array array) throws java.sql.SQLException {
        if (array == null) {
            return null;
        }
        List<Integer> minutes = new ArrayList<>();
        for (Object value : (Object[]) array.getArray()) {
            minutes.add(((Number) value).intValue());
        }
        return List.copyOf(minutes);
    }

    /** PostgreSQL's driver sends an Integer[] as an integer[]. */
    private static Integer[] array(List<Integer> minutes) {
        return minutes.toArray(Integer[]::new);
    }
}
