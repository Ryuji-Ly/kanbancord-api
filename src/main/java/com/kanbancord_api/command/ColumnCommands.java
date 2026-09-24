package com.kanbancord_api.command;

import com.kanbancord_api.dto.BoardColumnRequest;
import com.kanbancord_api.dto.BoardColumnResponse;
import com.kanbancord_api.dto.ColumnMoveRequest;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.model.Board;
import com.kanbancord_api.model.BoardColumn;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.BoardColumnService;
import com.kanbancord_api.service.ResourceValidator;
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
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;

    public ColumnCommands(
            BoardColumnService boardColumnService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator,
            ApplicationEventPublisher events) {
        this.boardColumnService = boardColumnService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
        this.events = events;
    }

    public BoardColumnResponse create(Long serverId, Long boardId, Long actorUserId, BoardColumnRequest request) {
        accessValidator.requireBoardPermission(actorUserId, serverId, boardId, "CREATE_COLUMN");
        resourceValidator.validatePathMatchesRequestId("boardId", boardId, request.getBoardId());
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateBoardNotArchived(board);

        BoardColumn column = new BoardColumn();
        column.setBoard(board);
        column.setName(request.getName());
        column.setPosition(request.getPosition() != null ? request.getPosition() : endOf(boardId));
        column.setColor(request.getColor());
        column.setWipLimit(request.getWipLimit());

        BoardColumnResponse created = BoardColumnResponse.from(boardColumnService.create(column));
        events.publishEvent(DomainEvent.created(EventType.COLUMN_CREATED, serverId, boardId, created.getColumnId(),
                actorUserId, created));
        return created;
    }

    public BoardColumnResponse update(Long serverId, Long boardId, Long columnId, Long actorUserId,
            BoardColumnRequest request) {
        accessValidator.requireBoardPermission(actorUserId, serverId, boardId, "EDIT_COLUMN");
        resourceValidator.validatePathMatchesRequestId("boardId", boardId, request.getBoardId());
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateBoardNotArchived(board);
        resourceValidator.validateColumnBelongsToBoard(columnId, boardId);
        BoardColumn column = resourceValidator.requireColumnInServer(columnId, serverId);
        // Only an actual change of position is a move; clients resending the current one need not MOVE_COLUMN.
        boolean moved = request.getPosition() != null
                && (column.getPosition() == null || request.getPosition().compareTo(column.getPosition()) != 0);
        if (moved) {
            accessValidator.requireBoardPermission(actorUserId, serverId, boardId, "MOVE_COLUMN");
        }

        BoardColumnResponse before = BoardColumnResponse.from(column);
        column.setName(request.getName());
        if (request.getPosition() != null) {
            column.setPosition(request.getPosition());
        }
        if (request.getColor() != null) {
            column.setColor(request.getColor());
        }
        if (request.getWipLimit() != null) {
            column.setWipLimit(request.getWipLimit());
        }

        BoardColumnResponse after = BoardColumnResponse.from(boardColumnService.update(column));
        events.publishEvent(DomainEvent.changed(EventType.COLUMN_UPDATED, serverId, boardId, columnId, actorUserId,
                before, after));
        return after;
    }

    /** Puts a column at a position on its board and renumbers the others. Needs only MOVE_COLUMN. */
    public BoardColumnResponse move(Long serverId, Long boardId, Long columnId, Long actorUserId,
            ColumnMoveRequest request) {
        accessValidator.requireBoardPermission(actorUserId, serverId, boardId, "MOVE_COLUMN");
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateBoardNotArchived(board);
        resourceValidator.validateColumnBelongsToBoard(columnId, boardId);
        BoardColumn column = resourceValidator.requireColumnInServer(columnId, serverId);

        BoardColumnResponse before = BoardColumnResponse.from(column);
        List<BoardColumn> columns = new ArrayList<>(boardColumnService.findByBoardIdOrdered(boardId));
        columns.removeIf(other -> other.getColumnId().equals(columnId));
        Positions.insert(columns, request.getIndex(), column);
        if (!Positions.renumber(columns, BoardColumn::getPosition, BoardColumn::setPosition)) {
            return before;
        }

        boardColumnService.updateAll(columns);
        BoardColumnResponse after = BoardColumnResponse.from(column);
        if (before.getPosition() == null || before.getPosition().compareTo(after.getPosition()) != 0) {
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
        accessValidator.requireBoardPermission(actorUserId, serverId, boardId, "DELETE_COLUMN");
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
