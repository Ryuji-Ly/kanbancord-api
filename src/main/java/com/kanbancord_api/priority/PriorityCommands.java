package com.kanbancord_api.priority;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.feature.Feature;
import com.kanbancord_api.feature.ServerFeatureService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Creating, editing, reordering and deleting a board's priority levels; all need MANAGE_PRIORITIES.
 * Positions are kept as 1..n in order. Deleting a level leaves its tasks without a priority.
 */
@Service
@Transactional
public class PriorityCommands {

    private static final String NEUTRAL_COLOR = "#64748b";

    private final BoardPriorityRepository boardPriorityRepository;
    private final BoardPriorityService boardPriorityService;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;
    private final ServerFeatureService features;

    public PriorityCommands(
            BoardPriorityRepository boardPriorityRepository,
            BoardPriorityService boardPriorityService,
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            ApplicationEventPublisher events,
            ServerFeatureService features) {
        this.boardPriorityRepository = boardPriorityRepository;
        this.boardPriorityService = boardPriorityService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.events = events;
        this.features = features;
    }

    /** Adds a level at the bottom of the list. */
    public BoardPriorityResponse create(Long serverId, Long boardId, Long actorUserId, BoardPriorityRequest request) {
        features.require(serverId, boardId, Feature.PRIORITIES);
        requireManage(serverId, boardId, actorUserId);
        String name = request.name().trim();
        requireUniqueName(boardId, name, null);

        List<BoardPriority> existing = boardPriorityRepository.findByBoardIdOrderByPositionAsc(boardId);
        BoardPriority priority = new BoardPriority();
        priority.setBoardId(boardId);
        priority.setName(name);
        priority.setColor(request.color() != null ? request.color() : NEUTRAL_COLOR);
        priority.setPosition(existing.size() + 1);

        BoardPriorityResponse created = BoardPriorityResponse.from(boardPriorityRepository.save(priority));
        events.publishEvent(DomainEvent.created(EventType.PRIORITY_CREATED, serverId, boardId, created.priorityId(),
                actorUserId, created));
        return created;
    }

    public BoardPriorityResponse update(Long serverId, Long boardId, Long priorityId, Long actorUserId,
            BoardPriorityRequest request) {
        features.require(serverId, boardId, Feature.PRIORITIES);
        requireManage(serverId, boardId, actorUserId);
        BoardPriority priority = boardPriorityService.requireInBoard(priorityId, boardId);
        String name = request.name().trim();
        requireUniqueName(boardId, name, priorityId);

        BoardPriorityResponse before = BoardPriorityResponse.from(priority);
        priority.setName(name);
        if (request.color() != null) {
            priority.setColor(request.color());
        }
        BoardPriorityResponse after = BoardPriorityResponse.from(boardPriorityRepository.save(priority));
        events.publishEvent(DomainEvent.changed(EventType.PRIORITY_UPDATED, serverId, boardId, priorityId, actorUserId,
                before, after));
        return after;
    }

    /** Moves a level to {@code index} (0-based) and renumbers the others. */
    public List<BoardPriorityResponse> move(Long serverId, Long boardId, Long priorityId, Long actorUserId, int index) {
        features.require(serverId, boardId, Feature.PRIORITIES);
        requireManage(serverId, boardId, actorUserId);
        BoardPriority priority = boardPriorityService.requireInBoard(priorityId, boardId);
        BoardPriorityResponse before = BoardPriorityResponse.from(priority);

        List<BoardPriority> ordered = new ArrayList<>(boardPriorityRepository.findByBoardIdOrderByPositionAsc(boardId));
        ordered.removeIf(candidate -> candidate.getPriorityId().equals(priorityId));
        ordered.add(Math.min(index, ordered.size()), priority);
        renumber(ordered);

        events.publishEvent(DomainEvent.changed(EventType.PRIORITY_MOVED, serverId, boardId, priorityId, actorUserId,
                before, BoardPriorityResponse.from(priority)));
        return ordered.stream().map(BoardPriorityResponse::from).toList();
    }

    public void delete(Long serverId, Long boardId, Long priorityId, Long actorUserId) {
        features.require(serverId, boardId, Feature.PRIORITIES);
        requireManage(serverId, boardId, actorUserId);
        BoardPriority priority = boardPriorityService.requireInBoard(priorityId, boardId);
        BoardPriorityResponse before = BoardPriorityResponse.from(priority);

        boardPriorityRepository.delete(priority);
        boardPriorityRepository.flush();
        renumber(boardPriorityRepository.findByBoardIdOrderByPositionAsc(boardId));

        events.publishEvent(DomainEvent.deleted(EventType.PRIORITY_DELETED, serverId, boardId, priorityId, actorUserId,
                before));
    }

    private void requireManage(Long serverId, Long boardId, Long actorUserId) {
        resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "MANAGE_PRIORITIES");
    }

    private void requireUniqueName(Long boardId, String name, Long exceptPriorityId) {
        boardPriorityRepository.findByBoardIdAndNameIgnoreCase(boardId, name)
                .filter(clash -> !clash.getPriorityId().equals(exceptPriorityId))
                .ifPresent(clash -> {
                    throw new BadRequestException("This board already has a priority named " + clash.getName());
                });
    }

    private void renumber(List<BoardPriority> ordered) {
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setPosition(i + 1);
        }
        boardPriorityRepository.saveAll(ordered);
    }
}
