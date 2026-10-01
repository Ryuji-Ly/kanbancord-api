package com.kanbancord_api.notify;

import com.kanbancord_api.access.Authorizer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * The bot's side of Discord notifications: reporting each server's channels, and collecting what to
 * deliver. Only the bot, with its internal token, may call these.
 */
@RestController
public class InternalNotificationController {

    private static final String BOT_TOKEN_HEADER = "X-Internal-Bot-Token";
    private static final int MAX_CLAIM = 50;

    private final NotificationQueue queue;
    private final NotificationRouter router;
    private final NotificationSettingsService settings;
    private final Authorizer authorizer;
    private final TaskThreadService threads;

    public InternalNotificationController(NotificationQueue queue, NotificationRouter router,
                                          NotificationSettingsService settings, Authorizer authorizer,
                                          TaskThreadService threads) {
        this.queue = queue;
        this.router = router;
        this.settings = settings;
        this.authorizer = authorizer;
        this.threads = threads;
    }

    /** {@code botCanThread} and {@code botCanPrivateThread} are left out by older bots: then false. */
    public record ChannelEntry(@NotBlank String channelId, @NotBlank String name, String category, Integer position,
                               @NotNull Boolean botCanPost, Boolean botCanThread, Boolean botCanPrivateThread) {
    }

    /** The server's text channels, complete: channels left out are removed. */
    @PutMapping("/api/internal/sync/servers/{serverId}/channels")
    public ResponseEntity<Void> replaceChannels(
            @PathVariable Long serverId,
            @RequestHeader(BOT_TOKEN_HEADER) String botToken,
            @Valid @RequestBody List<@Valid ChannelEntry> channels) {
        authorizer.requireInternalSyncAccess(botToken);
        settings.replaceChannels(serverId, channels.stream()
                .map(entry -> new NotificationSettingsService.Channel(Long.valueOf(entry.channelId()), entry.name(),
                        entry.category(), entry.position() == null ? 0 : entry.position(), entry.botCanPost(),
                        Boolean.TRUE.equals(entry.botCanThread()), Boolean.TRUE.equals(entry.botCanPrivateThread())))
                .toList());
        return ResponseEntity.noContent().build();
    }

    /**
     * Claims groups of changes that are due and says who hears about each. Groups with nobody to tell
     * are finished at once and not returned, and more are claimed in their place, so an empty answer
     * means nothing is due. Report each returned plan with delivered or failed.
     */
    @PostMapping("/api/internal/notifications/claim")
    public ResponseEntity<List<NotificationRouter.Plan>> claim(
            @RequestHeader(BOT_TOKEN_HEADER) String botToken,
            @RequestParam(defaultValue = "20") int limit) {
        authorizer.requireInternalSyncAccess(botToken);
        int wanted = Math.max(1, Math.min(limit, MAX_CLAIM));
        List<NotificationRouter.Plan> plans = new ArrayList<>();
        while (plans.size() < wanted) {
            List<NotificationQueue.Batch> batches = queue.claim(wanted - plans.size());
            if (batches.isEmpty()) {
                break;
            }
            for (NotificationQueue.Batch batch : batches) {
                NotificationRouter.Plan plan = router.route(batch);
                if (plan.isEmpty()) {
                    queue.markDelivered(batch.batchId());
                } else {
                    plans.add(plan);
                }
            }
        }
        return ResponseEntity.ok(plans);
    }

    /** The thread the bot made for a task, or {@code gone} when it found the thread deleted. */
    public record ThreadReport(@NotBlank String serverId, @NotNull Long taskId, @NotBlank String channelId,
                               @NotBlank String threadId, boolean privateThread, boolean gone) {
    }

    @PostMapping("/api/internal/notifications/threads")
    public ResponseEntity<Void> reportThread(@RequestHeader(BOT_TOKEN_HEADER) String botToken,
                                             @Valid @RequestBody ThreadReport report) {
        authorizer.requireInternalSyncAccess(botToken);
        if (report.gone()) {
            threads.forgetThread(report.taskId(), Long.valueOf(report.threadId()));
        } else {
            threads.recordThread(Long.valueOf(report.serverId()), report.taskId(), Long.valueOf(report.channelId()),
                    Long.valueOf(report.threadId()), report.privateThread());
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/internal/notifications/{batchId}/delivered")
    public ResponseEntity<Void> delivered(@PathVariable long batchId, @RequestHeader(BOT_TOKEN_HEADER) String botToken) {
        authorizer.requireInternalSyncAccess(botToken);
        queue.markDelivered(batchId);
        return ResponseEntity.noContent().build();
    }

    /** Delivery failed as a whole (Discord was unreachable, say): it is tried again later. */
    @PostMapping("/api/internal/notifications/{batchId}/failed")
    public ResponseEntity<Void> failed(@PathVariable long batchId, @RequestHeader(BOT_TOKEN_HEADER) String botToken) {
        authorizer.requireInternalSyncAccess(botToken);
        queue.release(batchId);
        return ResponseEntity.noContent().build();
    }
}
