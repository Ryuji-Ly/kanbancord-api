package com.kanbancord_api.notify;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A server's Discord notifications: the audit log channel and the update feeds. Server
 * administration, like the server's features; every change is in the audit log.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/notifications")
public class ServerNotificationController {

    private static final String PERMISSION = "MANAGE_SERVER_PERMISSIONS";

    private final NotificationSettingsService settings;
    private final Authorizer authorizer;
    private final ApplicationEventPublisher events;

    public ServerNotificationController(NotificationSettingsService settings, Authorizer authorizer,
                                       ApplicationEventPublisher events) {
        this.settings = settings;
        this.authorizer = authorizer;
        this.events = events;
    }

    /** Ids go out as strings: Discord ids are too large for a JavaScript number. */
    public record ChannelResponse(String channelId, String name, String category, int position, boolean botCanPost,
                                  boolean botCanThread, boolean botCanPrivateThread) {
    }

    /** {@code boardOverrides}: by board id, that board's own settings where they differ from the feed's. */
    public record FeedResponse(Long feedId, String channelId, List<Long> boardIds, Map<String, Boolean> events,
                               Map<String, Boolean> mentions, boolean mentionRoles, boolean interactive,
                               Map<String, BoardOverrideResponse> boardOverrides) {

        static FeedResponse from(NotificationSettingsService.Feed feed) {
            Map<String, Boolean> events = new LinkedHashMap<>();
            feed.events().forEach((event, on) -> events.put(event.name(), on));
            Map<String, Boolean> mentions = new LinkedHashMap<>();
            feed.mentions().forEach((event, on) -> mentions.put(event.name(), on));
            return new FeedResponse(feed.feedId(), String.valueOf(feed.channelId()), feed.boardIds(), events, mentions,
                    feed.mentionRoles(), feed.interactive(), BoardOverrideResponse.byBoard(feed));
        }
    }

    public record BoardOverrideResponse(Map<String, Boolean> events, Map<String, Boolean> mentions, int changes) {

        static Map<String, BoardOverrideResponse> byBoard(NotificationSettingsService.Feed feed) {
            Map<String, BoardOverrideResponse> result = new LinkedHashMap<>();
            feed.boardOverrides().forEach((boardId, own) -> result.put(String.valueOf(boardId), from(own)));
            return result;
        }

        static BoardOverrideResponse from(NotificationSettingsService.BoardOverride own) {
            Map<String, Boolean> events = new LinkedHashMap<>();
            own.events().forEach((event, on) -> events.put(event.name(), on));
            Map<String, Boolean> mentions = new LinkedHashMap<>();
            own.mentions().forEach((event, on) -> mentions.put(event.name(), on));
            return new BoardOverrideResponse(events, mentions, own.changes());
        }
    }

    public record SettingsResponse(String auditChannelId, List<FeedResponse> feeds, List<ChannelResponse> channels,
                                   List<Map<String, Object>> catalogue) {
    }

    public record AuditChannelRequest(String channelId) {
    }

    public record FeedRequest(String channelId, List<Long> boardIds, Map<String, Boolean> events,
                              Map<String, Boolean> mentions, Boolean mentionRoles, Boolean interactive) {
    }

    @GetMapping
    public ResponseEntity<SettingsResponse> get(@PathVariable Long serverId, @CurrentUser Long userId) {
        authorizer.requireServerPermission(userId, serverId, PERMISSION);
        return ResponseEntity.ok(current(serverId));
    }

    @PutMapping("/audit-channel")
    @Transactional
    public ResponseEntity<SettingsResponse> setAuditChannel(
            @PathVariable Long serverId, @CurrentUser Long userId, @RequestBody AuditChannelRequest request) {
        authorizer.requireServerPermission(userId, serverId, PERMISSION);
        String before = settings.auditChannel(serverId).map(String::valueOf).orElse(null);
        settings.setAuditChannel(serverId, channelId(request.channelId(), true));
        SettingsResponse after = current(serverId);
        audit(serverId, userId, Map.of("auditChannelId", nullable(before)), Map.of("auditChannelId", nullable(after.auditChannelId())));
        return ResponseEntity.ok(after);
    }

    @PostMapping("/feeds")
    @Transactional
    public ResponseEntity<FeedResponse> createFeed(
            @PathVariable Long serverId, @CurrentUser Long userId, @RequestBody FeedRequest request) {
        authorizer.requireServerPermission(userId, serverId, PERMISSION);
        FeedResponse created = FeedResponse.from(settings.saveFeed(serverId, null, channelId(request.channelId(), false),
                request.boardIds(), request.events(), request.mentions(), request.mentionRoles(), request.interactive()));
        audit(serverId, userId, null, feedSummary(created));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/feeds/{feedId}")
    @Transactional
    public ResponseEntity<FeedResponse> updateFeed(
            @PathVariable Long serverId, @PathVariable Long feedId, @CurrentUser Long userId,
            @RequestBody FeedRequest request) {
        authorizer.requireServerPermission(userId, serverId, PERMISSION);
        FeedResponse before = findFeed(serverId, feedId);
        FeedResponse updated = FeedResponse.from(settings.saveFeed(serverId, feedId,
                request.channelId() == null ? null : channelId(request.channelId(), false),
                request.boardIds(), request.events(), request.mentions(), request.mentionRoles(), request.interactive()));
        audit(serverId, userId, feedSummary(before), feedSummary(updated));
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/feeds/{feedId}")
    @Transactional
    public ResponseEntity<Void> deleteFeed(@PathVariable Long serverId, @PathVariable Long feedId, @CurrentUser Long userId) {
        authorizer.requireServerPermission(userId, serverId, PERMISSION);
        FeedResponse before = findFeed(serverId, feedId);
        settings.deleteFeed(serverId, feedId);
        audit(serverId, userId, feedSummary(before), null);
        return ResponseEntity.noContent().build();
    }

    private SettingsResponse current(Long serverId) {
        return new SettingsResponse(
                settings.auditChannel(serverId).map(String::valueOf).orElse(null),
                settings.feeds(serverId).stream().map(FeedResponse::from).toList(),
                settings.channels(serverId).stream()
                        .map(channel -> new ChannelResponse(String.valueOf(channel.channelId()), channel.name(),
                                channel.category(), channel.position(), channel.botCanPost(), channel.botCanThread(),
                                channel.botCanPrivateThread()))
                        .toList(),
                NotificationSettingsService.catalogue());
    }

    private FeedResponse findFeed(Long serverId, Long feedId) {
        return settings.feeds(serverId).stream().filter(feed -> feed.feedId().equals(feedId)).findFirst()
                .map(FeedResponse::from)
                .orElseThrow(() -> new com.kanbancord_api.exception.ResourceNotFoundException("Feed", "feedId", feedId));
    }

    /** The parts of a feed worth recording: where it posts, which boards, and which events are on. */
    private static Map<String, Object> feedSummary(FeedResponse feed) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("feedId", feed.feedId());
        summary.put("channelId", feed.channelId());
        summary.put("boardIds", feed.boardIds());
        summary.put("events", feed.events().entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList());
        summary.put("mentions", feed.mentions().entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList());
        summary.put("mentionRoles", feed.mentionRoles());
        summary.put("interactive", feed.interactive());
        return summary;
    }

    private void audit(Long serverId, Long userId, Object before, Object after) {
        EventType type = EventType.NOTIFICATIONS_UPDATED;
        DomainEvent event = before == null
                ? DomainEvent.created(type, serverId, null, serverId, userId, after)
                : after == null
                        ? DomainEvent.deleted(type, serverId, null, serverId, userId, before)
                        : DomainEvent.changed(type, serverId, null, serverId, userId, before, after);
        events.publishEvent(event);
    }

    private static Long channelId(String value, boolean optional) {
        if (value == null || value.isBlank()) {
            if (optional) {
                return null;
            }
            throw new BadRequestException("Pick a channel");
        }
        Long id = AuditClassifier.longOf(value);
        if (id == null) {
            throw new BadRequestException("Not a channel: " + value);
        }
        return id;
    }

    private static Object nullable(String value) {
        return value == null ? "" : value;
    }
}
