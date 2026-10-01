package com.kanbancord_api.notify;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.exception.BadRequestException;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A thread per task for one board, in one of the channels with a feed covering it: on or off, public
 * or private, and where updates go once a task has its thread. For the people who manage the board.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/threads")
public class BoardThreadController {

    private static final String PERMISSION = "EDIT_BOARD_DETAILS";

    private final TaskThreadService threads;
    private final NotificationSettingsService notificationSettings;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;

    public BoardThreadController(TaskThreadService threads, NotificationSettingsService notificationSettings,
                                 Authorizer authorizer, ResourceValidator resourceValidator,
                                 ApplicationEventPublisher events) {
        this.threads = threads;
        this.notificationSettings = notificationSettings;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.events = events;
    }

    /** A channel threads could go in: it has a feed covering the board. */
    public record FeedChannel(String channelId, String name, boolean botCanThread, boolean botCanPrivateThread) {
    }

    /**
     * {@code enabled}: switched on; {@code active}: and working (a feed covering the board still posts in
     * the chosen channel).
     */
    public record Threads(boolean enabled, boolean active, String channelId, boolean privateThreads, String updates,
                          List<FeedChannel> channels) {
    }

    public record ThreadsRequest(String channelId, Boolean privateThreads, String updates) {
    }

    @GetMapping
    public ResponseEntity<Threads> get(@PathVariable Long serverId, @PathVariable Long boardId,
                                       @CurrentUser Long userId) {
        requireAllowed(serverId, boardId, userId);
        return ResponseEntity.ok(current(serverId, boardId));
    }

    @PutMapping
    @Transactional
    public ResponseEntity<Threads> update(@PathVariable Long serverId, @PathVariable Long boardId,
                                          @CurrentUser Long userId, @RequestBody ThreadsRequest request) {
        requireAllowed(serverId, boardId, userId);
        Map<String, Object> before = summary(boardId);
        threads.save(serverId, boardId, channelId(request.channelId()), Boolean.TRUE.equals(request.privateThreads()),
                request.updates());
        audit(serverId, boardId, userId, before, summary(boardId));
        return ResponseEntity.ok(current(serverId, boardId));
    }

    @DeleteMapping
    @Transactional
    public ResponseEntity<Threads> disable(@PathVariable Long serverId, @PathVariable Long boardId,
                                           @CurrentUser Long userId) {
        requireAllowed(serverId, boardId, userId);
        Map<String, Object> before = summary(boardId);
        threads.clear(boardId);
        audit(serverId, boardId, userId, before, summary(boardId));
        return ResponseEntity.ok(current(serverId, boardId));
    }

    private void requireAllowed(Long serverId, Long boardId, Long userId) {
        resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(userId, serverId, boardId, PERMISSION);
    }

    private Threads current(Long serverId, Long boardId) {
        Set<Long> feedChannels = Set.copyOf(threads.feedChannels(serverId, boardId));
        List<FeedChannel> channels = notificationSettings.channels(serverId).stream()
                .filter(channel -> feedChannels.contains(channel.channelId()))
                .map(channel -> new FeedChannel(String.valueOf(channel.channelId()), channel.name(),
                        channel.botCanThread(), channel.botCanPrivateThread()))
                .toList();
        return threads.settings(boardId)
                .map(settings -> new Threads(true, feedChannels.contains(settings.channelId()),
                        String.valueOf(settings.channelId()), settings.privateThreads(), settings.updates().name(), channels))
                .orElse(new Threads(false, false, null, false, TaskThreadService.Updates.BOTH.name(), channels));
    }

    private Map<String, Object> summary(Long boardId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        threads.settings(boardId).ifPresentOrElse(settings -> {
            summary.put("taskThreads", true);
            summary.put("threadChannelId", String.valueOf(settings.channelId()));
            summary.put("privateThreads", settings.privateThreads());
            summary.put("threadUpdates", settings.updates().name());
        }, () -> summary.put("taskThreads", false));
        return summary;
    }

    private void audit(Long serverId, Long boardId, Long userId, Object before, Object after) {
        if (!before.equals(after)) {
            events.publishEvent(DomainEvent.changed(EventType.NOTIFICATIONS_UPDATED, serverId, boardId, serverId, userId,
                    before, after));
        }
    }

    private static Long channelId(String value) {
        if (value == null || !value.matches("\\d{1,20}")) {
            throw new BadRequestException("Choose the feed channel the threads go in");
        }
        return Long.valueOf(value);
    }
}
