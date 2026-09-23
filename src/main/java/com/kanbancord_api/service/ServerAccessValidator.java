package com.kanbancord_api.service;

import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.repository.BoardRepository;
import com.kanbancord_api.repository.ServerMemberRepository;
import com.kanbancord_api.repository.ServerRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ServerAccessValidator {

    private static final String VIEW_BOARD = "VIEW_BOARD";

    private final ServerMemberRepository serverMemberRepository;
    private final ServerRepository serverRepository;
    private final BoardRepository boardRepository;
    private final PermissionEvaluationService permissionEvaluationService;

    public ServerAccessValidator(
            ServerMemberRepository serverMemberRepository,
            ServerRepository serverRepository,
            BoardRepository boardRepository,
            PermissionEvaluationService permissionEvaluationService) {
        this.serverMemberRepository = serverMemberRepository;
        this.serverRepository = serverRepository;
        this.boardRepository = boardRepository;
        this.permissionEvaluationService = permissionEvaluationService;
    }

    /**
     * Validates that a user is a member of the specified server.
     * 
     * @throws AccessDeniedException     if user is not a member
     * @throws ResourceNotFoundException if server doesn't exist
     */
    public void validateUserInServer(Long userId, Long serverId) {
        if (!serverRepository.existsById(serverId)) {
            throw new ResourceNotFoundException("Server", "serverId", serverId);
        }

        if (!serverMemberRepository.existsByServer_ServerIdAndUser_UserId(serverId, userId)) {
            throw new AccessDeniedException("You are not a member of this server");
        }
    }

    /**
     * Validates that a user is a member of the server and holds a permission, evaluated at board
     * scope when {@code boardId} is given and at server scope otherwise.
     */
    public void validateUserHasPermission(Long userId, Long serverId, Long boardId, String requiredPermission) {
        validateUserInServer(userId, serverId);

        // Board rules are only meaningful for a board of this server; never evaluate a foreign board.
        if (boardId != null && !boardRepository.existsByBoardIdAndServer_ServerId(boardId, serverId)) {
            throw new ResourceNotFoundException("Board", "boardId", boardId);
        }

        if (requiredPermission == null || requiredPermission.isBlank()) {
            return;
        }

        // Everything inside a board requires seeing the board: a private board (VIEW_BOARD denied)
        // must not leak its tasks through VIEW_TASK or accept blind writes.
        if (boardId != null && !VIEW_BOARD.equals(requiredPermission)) {
            Map<String, PermissionEvaluationService.Decision> decisions = permissionEvaluationService
                    .resolveAll(serverId, boardId, userId, List.of(VIEW_BOARD, requiredPermission));
            if (!decisions.get(VIEW_BOARD).allowed()) {
                throw new AccessDeniedException("Missing required permission: " + VIEW_BOARD);
            }
            if (!decisions.get(requiredPermission).allowed()) {
                throw new AccessDeniedException("Missing required permission: " + requiredPermission);
            }
            return;
        }

        if (!permissionEvaluationService.isAllowed(serverId, boardId, userId, requiredPermission)) {
            throw new AccessDeniedException("Missing required permission: " + requiredPermission);
        }
    }
}
