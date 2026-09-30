package com.kanbancord_api.notify;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One board's settings for the update feeds that post about it: which events each posts, and which
 * of them mention people, where the board wants something other than the feed. Feeds themselves (their
 * channel and boards) are the server's, set by its managers; how they treat one board is for the
 * people who manage that board.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/notifications")
public class BoardNotificationController {

    private static final String PERMISSION = "EDIT_BOARD_DETAILS";

    private final NotificationSettingsService settings;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;

    public BoardNotificationController(NotificationSettingsService settings, Authorizer authorizer,
                                       ResourceValidator resourceValidator, ApplicationEventPublisher events) {
        this.settings = settings;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.events = events;
    }

    /**
     * A feed as it concerns this board: the feed's own settings, the board's differences, and what
     * that comes to.
     */
    public record BoardFeed(Long feedId, String channelId, String channelName, boolean everyBoard, boolean interactive,
                            Map<String, Boolean> feedEvents, Map<String, Boolean> feedMentions,
                            ServerNotificationController.BoardOverrideResponse own) {
    }

    public record BoardNotifications(List<BoardFeed> feeds, List<Map<String, Object>> catalogue) {
    }

    /** {@code null} for an event goes back to following the feed. */
    public record OverrideRequest(Map<String, Boolean> events, Map<String, Boolean> mentions) {
    }

    @GetMapping
    public ResponseEntity<BoardNotifications> get(@PathVariable Long serverId, @PathVariable Long boardId,
                                                  @CurrentUser Long userId) {
        requireAllowed(serverId, boardId, userId);
        return ResponseEntity.ok(current(serverId, boardId));
    }

    @PutMapping("/feeds/{feedId}")
    @Transactional
    public ResponseEntity<BoardNotifications> update(@PathVariable Long serverId, @PathVariable Long boardId,
                                                     @PathVariable Long feedId, @CurrentUser Long userId,
                                                     @RequestBody OverrideRequest request) {
        requireAllowed(serverId, boardId, userId);
        Map<String, Object> before = summary(serverId, feedId, boardId);
        settings.saveBoardOverride(serverId, feedId, boardId, request.events(), request.mentions());
        audit(serverId, boardId, userId, before, summary(serverId, feedId, boardId));
        return ResponseEntity.ok(current(serverId, boardId));
    }

    /** Back to following the feed entirely. */
    @DeleteMapping("/feeds/{feedId}")
    @Transactional
    public ResponseEntity<BoardNotifications> reset(@PathVariable Long serverId, @PathVariable Long boardId,
                                                    @PathVariable Long feedId, @CurrentUser Long userId) {
        requireAllowed(serverId, boardId, userId);
        Map<String, Object> before = summary(serverId, feedId, boardId);
        settings.clearBoardOverride(feedId, boardId);
        audit(serverId, boardId, userId, before, summary(serverId, feedId, boardId));
        return ResponseEntity.ok(current(serverId, boardId));
    }

    private void requireAllowed(Long serverId, Long boardId, Long userId) {
        resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(userId, serverId, boardId, PERMISSION);
    }

    private BoardNotifications current(Long serverId, Long boardId) {
        Map<Long, String> channelNames = new HashMap<>();
        settings.channels(serverId).forEach(channel -> channelNames.put(channel.channelId(), channel.name()));
        List<BoardFeed> feeds = settings.feeds(serverId).stream()
                .filter(feed -> feed.covers(boardId))
                .map(feed -> new BoardFeed(feed.feedId(), String.valueOf(feed.channelId()),
                        channelNames.get(feed.channelId()), feed.boardIds().isEmpty(), feed.interactive(),
                        names(feed.events()), names(feed.mentions()),
                        ServerNotificationController.BoardOverrideResponse.from(feed.boardOverrides().getOrDefault(boardId,
                                new NotificationSettingsService.BoardOverride(Map.of(), Map.of())))))
                .toList();
        return new BoardNotifications(feeds, NotificationSettingsService.catalogue());
    }

    private Map<String, Object> summary(Long serverId, Long feedId, Long boardId) {
        NotificationSettingsService.Feed feed = settings.feeds(serverId).stream()
                .filter(entry -> entry.feedId().equals(feedId)).findFirst()
                .orElseThrow(() -> new com.kanbancord_api.exception.ResourceNotFoundException("Feed", "feedId", feedId));
        NotificationSettingsService.BoardOverride own = feed.boardOverrides().get(boardId);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("feedId", feedId);
        summary.put("events", own == null ? Map.of() : names(own.events()));
        summary.put("mentions", own == null ? Map.of() : names(own.mentions()));
        return summary;
    }

    private static Map<String, Boolean> names(Map<NotificationEvent, Boolean> flags) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        flags.forEach((event, on) -> result.put(event.name(), on));
        return result;
    }

    private void audit(Long serverId, Long boardId, Long userId, Object before, Object after) {
        if (!before.equals(after)) {
            events.publishEvent(DomainEvent.changed(EventType.NOTIFICATIONS_UPDATED, serverId, boardId, serverId, userId,
                    before, after));
        }
    }
}
