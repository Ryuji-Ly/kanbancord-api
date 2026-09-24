package com.kanbancord_api.board;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.server.Server;
import com.kanbancord_api.server.ServerService;
import com.kanbancord_api.user.User;
import com.kanbancord_api.user.UserService;
import com.kanbancord_api.priority.BoardPriorityService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creating, changing, archiving and deleting boards, each in one transaction with its {@link DomainEvent}. */
@Service
@Transactional
public class BoardCommands {

    private final BoardService boardService;
    private final ServerService serverService;
    private final UserService userService;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;
    private final BoardPriorityService boardPriorityService;

    public BoardCommands(
            BoardService boardService,
            ServerService serverService,
            UserService userService,
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            ApplicationEventPublisher events,
            BoardPriorityService boardPriorityService) {
        this.boardService = boardService;
        this.serverService = serverService;
        this.userService = userService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.events = events;
        this.boardPriorityService = boardPriorityService;
    }

    public BoardResponse create(Long serverId, Long actorUserId, BoardRequest request) {
        authorizer.requireServerPermission(actorUserId, serverId, "CREATE_BOARD");
        resourceValidator.validatePathMatchesRequestId("serverId", serverId, request.getServerId());

        Server server = serverService.findById(serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Server", "serverId", serverId));
        resourceValidator.validateBoardNameUnique(request.getName(), serverId, null);

        Board board = new Board();
        board.setServer(server);
        board.setName(request.getName());
        board.setDescription(request.getDescription());
        board.setIsArchived(false);
        // The creator is always the actor; request.createdBy is ignored.
        User creator = userService.findById(actorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", actorUserId));
        board.setCreatedBy(creator);

        BoardResponse created = BoardResponse.from(boardService.create(board, request.getColumnNames()));
        boardPriorityService.createDefaults(created.getBoardId());
        events.publishEvent(DomainEvent.created(EventType.BOARD_CREATED, serverId, created.getBoardId(),
                created.getBoardId(), actorUserId, created));
        return created;
    }

    public BoardResponse update(Long serverId, Long boardId, Long actorUserId, BoardRequest request) {
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "EDIT_BOARD_DETAILS");
        resourceValidator.validatePathMatchesRequestId("serverId", serverId, request.getServerId());
        resourceValidator.validateBoardNameUnique(request.getName(), serverId, boardId);

        BoardResponse before = BoardResponse.from(board);
        board.setName(request.getName());
        if (request.getDescription() != null) {
            board.setDescription(request.getDescription());
        }

        BoardResponse after = BoardResponse.from(boardService.update(board));
        events.publishEvent(DomainEvent.changed(EventType.BOARD_UPDATED, serverId, boardId, boardId, actorUserId,
                before, after));
        return after;
    }

    public BoardResponse setArchived(Long serverId, Long boardId, Long actorUserId, boolean archived) {
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "ARCHIVE_BOARD");

        BoardResponse before = BoardResponse.from(board);
        board.setIsArchived(archived);

        BoardResponse after = BoardResponse.from(boardService.update(board));
        events.publishEvent(DomainEvent.changed(archived ? EventType.BOARD_ARCHIVED : EventType.BOARD_RESTORED,
                serverId, boardId, boardId, actorUserId, before, after));
        return after;
    }

    public void delete(Long serverId, Long boardId, Long actorUserId) {
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(actorUserId, serverId, boardId, "DELETE_BOARD");

        BoardResponse before = BoardResponse.from(board);
        boardService.deleteById(board.getBoardId());
        events.publishEvent(DomainEvent.deleted(EventType.BOARD_DELETED, serverId, boardId, boardId, actorUserId,
                before));
    }
}
