package com.kanbancord_api.command;

import com.kanbancord_api.dto.TaskCommentRequest;
import com.kanbancord_api.dto.TaskCommentResponse;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Task;
import com.kanbancord_api.model.TaskComment;
import com.kanbancord_api.model.TaskCommentEdit;
import com.kanbancord_api.model.User;
import com.kanbancord_api.repository.TaskCommentEditRepository;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.service.TaskCommentService;
import com.kanbancord_api.service.UserService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writing, editing and deleting task comments. Authors manage their own comments with the permission
 * that lets them comment at all; EDIT_TASK_COMMENT and DELETE_TASK_COMMENT moderate other people's.
 */
@Service
@Transactional
public class TaskCommentCommands {

    private final TaskCommentService taskCommentService;
    private final TaskCommentEditRepository taskCommentEditRepository;
    private final UserService userService;
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;

    public TaskCommentCommands(
            TaskCommentService taskCommentService,
            TaskCommentEditRepository taskCommentEditRepository,
            UserService userService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator,
            ApplicationEventPublisher events) {
        this.taskCommentService = taskCommentService;
        this.taskCommentEditRepository = taskCommentEditRepository;
        this.userService = userService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
        this.events = events;
    }

    public TaskCommentResponse create(Long serverId, Long boardId, Long taskId, Long actorUserId,
            TaskCommentRequest request) {
        accessValidator.requireBoardPermission(actorUserId, serverId, boardId, "CREATE_TASK_COMMENT");
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, request.getTaskId());
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);
        Task task = resourceValidator.requireTaskInServer(taskId, serverId);

        TaskComment comment = new TaskComment();
        comment.setTask(task);
        comment.setUser(requireUser(actorUserId));
        comment.setContent(request.getContent());
        if (request.getReplyToId() != null) {
            TaskComment replyTo = resourceValidator.requireCommentInServer(request.getReplyToId(), serverId);
            resourceValidator.validatePathMatchesRequestId("taskId", taskId, replyTo.getTask().getTaskId());
            comment.setReplyTo(replyTo);
        }

        TaskCommentResponse created = TaskCommentResponse.from(taskCommentService.create(comment));
        events.publishEvent(DomainEvent.created(EventType.TASK_COMMENT_CREATED, serverId, boardId,
                created.getCommentId(), actorUserId, created));
        return created;
    }

    public TaskCommentResponse edit(Long serverId, Long boardId, Long taskId, Long commentId, Long actorUserId,
            TaskCommentRequest request) {
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, request.getTaskId());
        TaskComment comment = requireComment(serverId, boardId, taskId, commentId);
        accessValidator.requireBoardPermission(actorUserId, serverId, boardId,
                isAuthor(comment, actorUserId) ? "CREATE_TASK_COMMENT" : "EDIT_TASK_COMMENT");

        TaskCommentResponse before = TaskCommentResponse.from(comment);
        comment.setContent(request.getContent());
        TaskComment updated = taskCommentService.update(comment);

        TaskCommentEdit edit = new TaskCommentEdit();
        edit.setTaskComment(updated);
        edit.setEditor(requireUser(actorUserId));
        taskCommentEditRepository.save(edit);
        updated.getEdits().add(edit);

        TaskCommentResponse after = TaskCommentResponse.from(updated);
        events.publishEvent(DomainEvent.changed(EventType.TASK_COMMENT_UPDATED, serverId, boardId, commentId,
                actorUserId, before, after));
        return after;
    }

    public void delete(Long serverId, Long boardId, Long taskId, Long commentId, Long actorUserId) {
        TaskComment comment = requireComment(serverId, boardId, taskId, commentId);
        accessValidator.requireBoardPermission(actorUserId, serverId, boardId,
                isAuthor(comment, actorUserId) ? "CREATE_TASK_COMMENT" : "DELETE_TASK_COMMENT");

        TaskCommentResponse before = TaskCommentResponse.from(comment);
        taskCommentService.deleteById(comment.getCommentId());
        events.publishEvent(DomainEvent.deleted(EventType.TASK_COMMENT_DELETED, serverId, boardId, commentId,
                actorUserId, before));
    }

    private TaskComment requireComment(Long serverId, Long boardId, Long taskId, Long commentId) {
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);
        TaskComment comment = resourceValidator.requireCommentInServer(commentId, serverId);
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, comment.getTask().getTaskId());
        return comment;
    }

    private User requireUser(Long userId) {
        return userService.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));
    }

    private static boolean isAuthor(TaskComment comment, Long userId) {
        return comment.getUser() != null && userId.equals(comment.getUser().getUserId());
    }
}
