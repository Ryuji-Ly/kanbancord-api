package com.kanbancord_api.user;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The signed-in user's time zone, which the bot uses to read times typed in Discord (Discord does not tell bots
 * anyone's). The website sends the device's own as detected; one chosen with /notifications wins over that.
 */
@RestController
@RequestMapping("/api/me/time-zone")
public class TimeZoneController {

    /** "UTC+8", "GMT-03:30", "+5:30" and the like: a fixed offset rather than a place. */
    private static final Pattern OFFSET =
            Pattern.compile("^(?:UTC|GMT)?\\s*([+-])\\s*(\\d{1,2})(?::?(\\d{2}))?$", Pattern.CASE_INSENSITIVE);

    /** @param timeZone a place such as Europe/Berlin, a fixed offset such as +08:00, or null when not known */
    public record TimeZone(String timeZone, boolean chosen) {
    }

    /**
     * @param timeZone null forgets it, so the website sets it again from the device
     * @param detected true when the website read it from the device; it does not replace one the person chose
     */
    public record Change(String timeZone, Boolean detected) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final Authorizer authorizer;

    public TimeZoneController(JdbcTemplate jdbcTemplate, Authorizer authorizer) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizer = authorizer;
    }

    @GetMapping
    public ResponseEntity<TimeZone> get(@CurrentUser Long userId) {
        return ResponseEntity.ok(current(userId));
    }

    @PutMapping
    @Transactional
    public ResponseEntity<TimeZone> update(@CurrentUser Long userId, @RequestBody Change change) {
        authorizer.requireAuthenticatedUserId();
        boolean detected = Boolean.TRUE.equals(change.detected());
        if (detected && (change.timeZone() == null || current(userId).chosen())) {
            return ResponseEntity.ok(current(userId));
        }
        String zone = change.timeZone() == null ? null : normalise(change.timeZone());
        jdbcTemplate.update("UPDATE users SET time_zone = ?, time_zone_chosen = ? WHERE user_id = ?",
                zone, zone != null && !detected, userId);
        return ResponseEntity.ok(current(userId));
    }

    private TimeZone current(Long userId) {
        List<TimeZone> found = jdbcTemplate.query("SELECT time_zone, time_zone_chosen FROM users WHERE user_id = ?",
                (rs, row) -> new TimeZone(rs.getString("time_zone"), rs.getBoolean("time_zone_chosen")), userId);
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("User", "userId", userId);
        }
        return found.get(0);
    }

    /**
     * A place's own name (as Java and browsers both know it), or an offset as "+08:00". Anything else is refused,
     * so the bot never stores a zone it cannot read times in.
     */
    static String normalise(String value) {
        String text = value.trim();
        Matcher offset = OFFSET.matcher(text);
        if (offset.matches()) {
            int hours = Integer.parseInt(offset.group(2));
            int minutes = offset.group(3) == null ? 0 : Integer.parseInt(offset.group(3));
            // Java allows up to 18 hours; no place on Earth is more than 14 from UTC.
            if (hours * 60 + minutes > 14 * 60) {
                throw new BadRequestException("Offsets go from UTC-14:00 to UTC+14:00");
            }
            try {
                ZoneOffset zone = ZoneOffset.ofHoursMinutes(
                        "-".equals(offset.group(1)) ? -hours : hours, "-".equals(offset.group(1)) ? -minutes : minutes);
                return zone.getTotalSeconds() == 0 ? "UTC" : zone.getId();
            } catch (DateTimeException e) {
                throw new BadRequestException("Offsets go from UTC-14:00 to UTC+14:00");
            }
        }
        if (text.equalsIgnoreCase("UTC") || text.equalsIgnoreCase("GMT")) {
            return "UTC";
        }
        for (String id : ZoneId.getAvailableZoneIds()) {
            if (id.equalsIgnoreCase(text) && id.contains("/")) {
                return id;
            }
        }
        throw new BadRequestException("Not a time zone: give a place such as Europe/Berlin, or an offset such as UTC+8");
    }
}
