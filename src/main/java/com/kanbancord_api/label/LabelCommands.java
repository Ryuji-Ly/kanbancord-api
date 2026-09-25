package com.kanbancord_api.label;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.board.Board;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.feature.Feature;
import com.kanbancord_api.feature.ServerFeatureService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creating, editing and deleting the labels of a board. */
@Service
@Transactional
public class LabelCommands {

    private final LabelService labelService;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;
    private final ServerFeatureService features;

    public LabelCommands(
            LabelService labelService,
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            ApplicationEventPublisher events,
            ServerFeatureService features) {
        this.labelService = labelService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.events = events;
        this.features = features;
    }

    public LabelResponse create(Long serverId, Long boardId, Long actorUserId, LabelRequest request) {
        features.require(serverId, boardId, Feature.LABELS);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "CREATE_LABEL");
        resourceValidator.validatePathMatchesRequestId("boardId", boardId, request.getBoardId());
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateLabelNameUnique(request.getName(), boardId, null);

        Label label = new Label();
        label.setBoard(board);
        label.setName(request.getName());
        label.setColor(request.getColor());

        LabelResponse created = LabelResponse.from(labelService.create(label));
        events.publishEvent(DomainEvent.created(EventType.LABEL_CREATED, serverId, boardId, created.getLabelId(),
                actorUserId, created));
        return created;
    }

    public LabelResponse update(Long serverId, Long boardId, Long labelId, Long actorUserId, LabelRequest request) {
        features.require(serverId, boardId, Feature.LABELS);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "EDIT_LABEL");
        resourceValidator.validatePathMatchesRequestId("boardId", boardId, request.getBoardId());
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateLabelBelongsToBoard(labelId, boardId);
        Label label = resourceValidator.requireLabelInServer(labelId, serverId);
        resourceValidator.validateLabelNameUnique(request.getName(), boardId, labelId);

        LabelResponse before = LabelResponse.from(label);
        label.setName(request.getName());
        label.setColor(request.getColor());

        LabelResponse updated = LabelResponse.from(labelService.update(label));
        events.publishEvent(DomainEvent.changed(EventType.LABEL_UPDATED, serverId, boardId, labelId, actorUserId,
                before, updated));
        return updated;
    }

    public void delete(Long serverId, Long boardId, Long labelId, Long actorUserId) {
        features.require(serverId, boardId, Feature.LABELS);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "DELETE_LABEL");
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateLabelBelongsToBoard(labelId, boardId);
        Label label = resourceValidator.requireLabelInServer(labelId, serverId);

        LabelResponse before = LabelResponse.from(label);
        labelService.deleteById(label.getLabelId());
        events.publishEvent(DomainEvent.deleted(EventType.LABEL_DELETED, serverId, boardId, labelId, actorUserId,
                before));
    }
}
