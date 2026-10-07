package com.kanbancord_api.board;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.common.Positions;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Creating, changing and deleting board columns, each in one transaction with its {@link DomainEvent}. */
@Service
@Transactional
public class ColumnCommands {

    private final BoardColumnService boardColumnService;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;

    public ColumnCommands(
            BoardColumnService boardColumnService,
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            ApplicationEventPublisher events) {
        this.boardColumnService = boardColumnService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.events = events;
    }

    public BoardColumnResponse create(Long serverId, Long boardId, Long actorUserId, BoardColumnRequest request) {
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "CREATE_COLUMN");
        resourceValidator.validatePathMatchesRequestId("boardId", boardId, request.boardId());
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateBoardNotArchived(board);

        BoardColumn column = new BoardColumn();
        column.setBoard(board);
        column.setName(request.name());
        column.setPosition(request.position() != null ? request.position() : endOf(boardId));
        column.setColor(request.color());
        column.setWipLimit(request.wipLimit());

        BoardColumnResponse created = BoardColumnResponse.from(boardColumnService.create(column));
        events.publishEvent(DomainEvent.created(EventType.COLUMN_CREATED, serverId, boardId, created.columnId(),
                actorUserId, created));
        return created;
    }

    public BoardColumnResponse update(Long serverId, Long boardId, Long columnId, Long actorUserId,
            BoardColumnRequest request) {
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "EDIT_COLUMN");
        resourceValidator.validatePathMatchesRequestId("boardId", boardId, request.boardId());
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateBoardNotArchived(board);
        resourceValidator.validateColumnBelongsToBoard(columnId, boardId);
        BoardColumn column = resourceValidator.requireColumnInServer(columnId, serverId);
        // Only an actual change of position is a move; clients resending the current one need not MOVE_COLUMN.
        boolean moved = request.position() != null
                && (column.getPosition() == null || request.position().compareTo(column.getPosition()) != 0);
        if (moved) {
            authorizer.requireBoardPermission(actorUserId, serverId, boardId, "MOVE_COLUMN");
        }

        BoardColumnResponse before = BoardColumnResponse.from(column);
        column.setName(request.name());
        if (request.position() != null) {
            column.setPosition(request.position());
        }
        if (request.color() != null) {
            column.setColor(request.color());
        }
        if (request.wipLimit() != null) {
            column.setWipLimit(request.wipLimit());
        }

        BoardColumnResponse after = BoardColumnResponse.from(boardColumnService.update(column));
        events.publishEvent(DomainEvent.changed(EventType.COLUMN_UPDATED, serverId, boardId, columnId, actorUserId,
                before, after));
        return after;
    }

    /** Puts a column at a position on its board and renumbers the others. Needs only MOVE_COLUMN. */
    public BoardColumnResponse move(Long serverId, Long boardId, Long columnId, Long actorUserId,
            ColumnMoveRequest request) {
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "MOVE_COLUMN");
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateBoardNotArchived(board);
        resourceValidator.validateColumnBelongsToBoard(columnId, boardId);
        BoardColumn column = resourceValidator.requireColumnInServer(columnId, serverId);

        BoardColumnResponse before = BoardColumnResponse.from(column);
        List<BoardColumn> columns = new ArrayList<>(boardColumnService.findByBoardIdOrdered(boardId));
        columns.removeIf(other -> other.getColumnId().equals(columnId));
        Positions.insert(columns, request.index(), column);
        if (!Positions.renumber(columns, BoardColumn::getPosition, BoardColumn::setPosition)) {
            return before;
        }

        boardColumnService.updateAll(columns);
        BoardColumnResponse after = BoardColumnResponse.from(column);
        if (before.position() == null || before.position().compareTo(after.position()) != 0) {
            events.publishEvent(DomainEvent.changed(EventType.COLUMN_MOVED, serverId, boardId, columnId,
                    actorUserId, before, after));
        }
        return after;
    }

    /** The position after the board's last column, so columns created without one go to the end. */
    private BigDecimal endOf(Long boardId) {
        return boardColumnService.findByBoardIdOrdered(boardId).stream()
                .map(BoardColumn::getPosition)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .map(last -> last.setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE))
                .orElse(BigDecimal.ONE);
    }

    public void delete(Long serverId, Long boardId, Long columnId, Long actorUserId) {
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "DELETE_COLUMN");
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateBoardNotArchived(board);
        resourceValidator.validateColumnBelongsToBoard(columnId, boardId);
        BoardColumn column = resourceValidator.requireColumnInServer(columnId, serverId);

        BoardColumnResponse before = BoardColumnResponse.from(column);
        boardColumnService.deleteById(column.getColumnId());
        events.publishEvent(DomainEvent.deleted(EventType.COLUMN_DELETED, serverId, boardId, columnId, actorUserId,
                before));
    }
}
