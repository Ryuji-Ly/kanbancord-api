package com.kanbancord_api.task;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.user.User;
import com.kanbancord_api.user.UserService;
import com.kanbancord_api.feature.Feature;
import com.kanbancord_api.feature.ServerFeatureService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writing, editing and deleting task comments. A comment is only ever edited by its author, with the
 * permission that lets them comment at all: nobody can put words in someone else's mouth. Deleting
 * other people's comments is moderation, with DELETE_TASK_COMMENT.
 */
@Service
@Transactional
public class TaskCommentCommands {

    private final TaskCommentService taskCommentService;
    private final TaskCommentEditRepository taskCommentEditRepository;
    private final UserService userService;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;
    private final ServerFeatureService features;

    public TaskCommentCommands(
            TaskCommentService taskCommentService,
            TaskCommentEditRepository taskCommentEditRepository,
            UserService userService,
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            ApplicationEventPublisher events,
            ServerFeatureService features) {
        this.taskCommentService = taskCommentService;
        this.taskCommentEditRepository = taskCommentEditRepository;
        this.userService = userService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.events = events;
        this.features = features;
    }

    public TaskCommentResponse create(Long serverId, Long boardId, Long taskId, Long actorUserId,
            TaskCommentRequest request) {
        features.require(serverId, boardId, Feature.COMMENTS);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "CREATE_TASK_COMMENT");
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, request.taskId());
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);
        Task task = resourceValidator.requireTaskInServer(taskId, serverId);

        TaskComment comment = new TaskComment();
        comment.setTask(task);
        comment.setUser(requireUser(actorUserId));
        comment.setContent(request.content());
        if (request.replyToId() != null) {
            TaskComment replyTo = resourceValidator.requireCommentInServer(request.replyToId(), serverId);
            resourceValidator.validatePathMatchesRequestId("taskId", taskId, replyTo.getTask().getTaskId());
            comment.setReplyTo(replyTo);
        }

        TaskCommentResponse created = TaskCommentResponse.from(taskCommentService.create(comment));
        events.publishEvent(DomainEvent.created(EventType.TASK_COMMENT_CREATED, serverId, boardId,
                created.commentId(), actorUserId, created));
        return created;
    }

    public TaskCommentResponse edit(Long serverId, Long boardId, Long taskId, Long commentId, Long actorUserId,
            TaskCommentRequest request) {
        features.require(serverId, boardId, Feature.COMMENTS);
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, request.taskId());
        TaskComment comment = requireComment(serverId, boardId, taskId, commentId);
        if (!isAuthor(comment, actorUserId)) {
            throw new AccessDeniedException("Only the person who wrote a comment can edit it");
        }
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "CREATE_TASK_COMMENT");

        TaskCommentResponse before = TaskCommentResponse.from(comment);
        comment.setContent(request.content());
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
        features.require(serverId, boardId, Feature.COMMENTS);
        TaskComment comment = requireComment(serverId, boardId, taskId, commentId);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId,
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
