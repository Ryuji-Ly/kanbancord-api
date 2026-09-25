package com.kanbancord_api.label;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.task.Task;
import com.kanbancord_api.feature.Feature;
import com.kanbancord_api.feature.ServerFeatureService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applying the labels of a board to its tasks, and removing them. */
@Service
@Transactional
public class TaskLabelCommands {

    private final TaskLabelService taskLabelService;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;
    private final ServerFeatureService features;

    public TaskLabelCommands(
            TaskLabelService taskLabelService,
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            ApplicationEventPublisher events,
            ServerFeatureService features) {
        this.taskLabelService = taskLabelService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.events = events;
        this.features = features;
    }

    public TaskLabelResponse add(Long serverId, Long boardId, Long taskId, Long actorUserId, TaskLabelRequest request) {
        features.require(serverId, boardId, Feature.LABELS);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "APPLY_LABEL_TO_TASK");
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, request.getTaskId());
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);
        Task task = resourceValidator.requireTaskInServer(taskId, serverId);
        Label label = resourceValidator.requireLabelInServer(request.getLabelId(), serverId);
        resourceValidator.validateLabelBelongsToBoard(label.getLabelId(), boardId);

        TaskLabel taskLabel = new TaskLabel();
        taskLabel.setTask(task);
        taskLabel.setLabel(label);

        TaskLabelResponse created = TaskLabelResponse.from(taskLabelService.create(taskLabel));
        events.publishEvent(DomainEvent.created(EventType.TASK_LABEL_ADDED, serverId, boardId, created.getId(),
                actorUserId, created));
        return created;
    }

    public void remove(Long serverId, Long boardId, Long taskId, Long taskLabelId, Long actorUserId) {
        features.require(serverId, boardId, Feature.LABELS);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "REMOVE_LABEL_FROM_TASK");
        resourceValidator.validateTaskBelongsToBoard(taskId, boardId);
        TaskLabel taskLabel = resourceValidator.requireTaskLabelInServer(taskLabelId, serverId);
        resourceValidator.validatePathMatchesRequestId("taskId", taskId, taskLabel.getTask().getTaskId());

        TaskLabelResponse before = TaskLabelResponse.from(taskLabel);
        taskLabelService.deleteById(taskLabel.getId());
        events.publishEvent(DomainEvent.deleted(EventType.TASK_LABEL_REMOVED, serverId, boardId, taskLabelId,
                actorUserId, before));
    }
}
