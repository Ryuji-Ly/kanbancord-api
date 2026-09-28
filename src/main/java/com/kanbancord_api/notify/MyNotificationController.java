package com.kanbancord_api.notify;

import com.kanbancord_api.event.UserEvent;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the signed-in user wants by direct message from the bot. Changes apply to every device at
 * once, through the user's own realtime channel.
 */
@RestController
@RequestMapping("/api/me/notifications")
public class MyNotificationController {

    private final NotificationSettingsService settings;
    private final ApplicationEventPublisher events;

    public MyNotificationController(NotificationSettingsService settings, ApplicationEventPublisher events) {
        this.settings = settings;
        this.events = events;
    }

    /**
     * @param servers per server id (as a string), DEFAULT, ASSIGNMENTS or NONE; servers not listed use DEFAULT
     */
    public record Response(String dmMode, Map<String, Boolean> events, boolean includeCommented,
                           boolean includeFollowed, Map<String, String> servers, List<Map<String, Object>> catalogue) {

        static Response from(NotificationSettingsService.UserSettings mine) {
            Map<String, Boolean> events = new LinkedHashMap<>();
            mine.events().forEach((event, on) -> events.put(event.name(), on));
            Map<String, String> servers = new LinkedHashMap<>();
            mine.servers().forEach((id, mode) -> servers.put(String.valueOf(id), mode.name()));
            return new Response(mine.dmMode().name(), events, mine.includeCommented(), mine.includeFollowed(), servers,
                    NotificationSettingsService.catalogue());
        }
    }

    @GetMapping
    public ResponseEntity<Response> get(@CurrentUser Long userId) {
        return ResponseEntity.ok(Response.from(settings.userSettings(userId)));
    }

    /** Changes only what is sent: {"events": {"TASK_DUE": false}} leaves everything else as it was. */
    @PutMapping
    public ResponseEntity<Response> update(@CurrentUser Long userId, @RequestBody Map<String, Object> changes) {
        Response updated = Response.from(settings.updateUserSettings(userId, changes));
        events.publishEvent(new UserEvent(UserEvent.Type.NOTIFICATION_SETTINGS_CHANGED, userId, Map.of()));
        return ResponseEntity.ok(updated);
    }
}
