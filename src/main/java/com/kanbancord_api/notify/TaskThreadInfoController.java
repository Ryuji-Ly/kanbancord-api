package com.kanbancord_api.notify;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.permission.PermissionEvaluationService;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One task's thread, for the "Discuss in thread" button: whether it has one, and if not, the thread
 * the bot should make (or, with threads off, whether the person asking may switch them on, and where).
 */
@RestController
public class TaskThreadInfoController {

    private final TaskThreadService threads;
    private final NotificationSettingsService notificationSettings;
    private final PermissionEvaluationService permissions;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final JdbcTemplate jdbcTemplate;

    public TaskThreadInfoController(TaskThreadService threads, NotificationSettingsService notificationSettings,
                                    PermissionEvaluationService permissions, Authorizer authorizer,
                                    ResourceValidator resourceValidator, JdbcTemplate jdbcTemplate) {
        this.threads = threads;
        this.notificationSettings = notificationSettings;
        this.permissions = permissions;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@code available}: a feed covers the board; {@code enabled}: threads are on and working;
     * {@code canEnable}: the person may switch them on (then {@code channels} lists where they could go);
     * {@code members}: who a private thread includes (the task's creator and assignees).
     */
    public record TaskThreadInfo(boolean available, boolean enabled, boolean canEnable, String threadId,
                                 String channelId, boolean privateThread, String name, List<String> members,
                                 List<BoardThreadController.FeedChannel> channels) {
    }

    @GetMapping("/api/servers/{serverId}/boards/{boardId}/tasks/{taskId}/thread")
    public ResponseEntity<TaskThreadInfo> get(@PathVariable Long serverId, @PathVariable Long boardId,
                                              @PathVariable Long taskId, @CurrentUser Long userId) {
        resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_TASK");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT title, created_by FROM tasks WHERE task_id = ? AND board_id = ?", taskId, boardId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Task", "taskId", taskId);
        }
        String title = (String) rows.get(0).get("title");

        Set<Long> feedChannels = Set.copyOf(threads.feedChannels(serverId, boardId));
        Optional<TaskThreadService.Settings> on = threads.active(serverId, boardId);
        boolean canEnable = permissions.isAllowed(serverId, boardId, userId, "EDIT_BOARD_DETAILS");
        List<BoardThreadController.FeedChannel> channels = canEnable
                ? notificationSettings.channels(serverId).stream()
                        .filter(channel -> feedChannels.contains(channel.channelId()))
                        .map(channel -> new BoardThreadController.FeedChannel(String.valueOf(channel.channelId()),
                                channel.name(), channel.botCanThread(), channel.botCanPrivateThread()))
                        .toList()
                : List.of();
        if (on.isEmpty()) {
            return ResponseEntity.ok(new TaskThreadInfo(!feedChannels.isEmpty(), false, canEnable, null, null, false,
                    title, List.of(), channels));
        }

        TaskThreadService.Settings settings = on.get();
        Optional<TaskThreadService.TaskThread> thread = threads.thread(taskId)
                .filter(found -> found.channelId().equals(settings.channelId()));
        boolean privateThread = thread.map(TaskThreadService.TaskThread::privateThread).orElse(settings.privateThreads());
        List<String> members = new ArrayList<>();
        if (privateThread) {
            Object creator = rows.get(0).get("created_by");
            if (creator != null) {
                members.add(String.valueOf(creator));
            }
            jdbcTemplate.queryForList("SELECT user_id FROM task_assignments WHERE task_id = ?", Long.class, taskId)
                    .stream().map(String::valueOf).filter(id -> !members.contains(id)).forEach(members::add);
        }
        return ResponseEntity.ok(new TaskThreadInfo(true, true, canEnable,
                thread.map(found -> String.valueOf(found.threadId())).orElse(null), String.valueOf(settings.channelId()),
                privateThread, title, members, channels));
    }
}
