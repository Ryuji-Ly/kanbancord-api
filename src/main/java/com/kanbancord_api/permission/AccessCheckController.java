package com.kanbancord_api.permission;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.board.Board;
import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Checks what someone may do, and why: the caller themselves, another member, a member with other
 * roles, or anyone with a set of roles. Checking anyone but yourself as you are takes the right to
 * manage the server's permissions, or on one board, that board's.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/permissions/check")
public class AccessCheckController {

    private final AccessCheckService accessCheckService;
    private final PermissionEvaluationService evaluation;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;

    public AccessCheckController(AccessCheckService accessCheckService, PermissionEvaluationService evaluation,
                                 Authorizer authorizer, ResourceValidator resourceValidator) {
        this.accessCheckService = accessCheckService;
        this.evaluation = evaluation;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
    }

    /**
     * @param userId    the member to check; the caller when left out and {@code withRoles} is not set
     * @param withRoles check with {@code roleIds} instead of the member's own roles (or, without a
     *                  member, for anyone with those roles)
     * @param boardId   check on this board rather than server-wide
     */
    @GetMapping
    public ResponseEntity<AccessCheckService.AccessCheck> check(
            @PathVariable Long serverId,
            @CurrentUser Long actorUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "false") boolean withRoles,
            @RequestParam(required = false) List<Long> roleIds,
            @RequestParam(required = false) Long boardId) {
        authorizer.requireUserInServer(actorUserId, serverId);
        Board board = boardId == null ? null : resourceValidator.requireBoardInServer(boardId, serverId);

        Long subject = userId == null && !withRoles ? actorUserId : userId;
        boolean self = actorUserId.equals(subject) && !withRoles;
        if (self) {
            if (board != null) {
                authorizer.requireBoardPermission(actorUserId, serverId, boardId, "VIEW_BOARD");
            }
        } else if (!mayCheckOthers(actorUserId, serverId, boardId)) {
            throw new AccessDeniedException(board == null
                    ? "Checking other people's access takes the right to manage the server's permissions"
                    : "Checking other people's access on this board takes the right to edit its permissions");
        }
        if (subject != null && !self) {
            resourceValidator.validatePermissionSubjectBelongsToServer("USER", subject, serverId);
        }

        List<Long> roles = withRoles ? (roleIds == null ? List.of() : roleIds) : null;
        return ResponseEntity.ok(accessCheckService.check(serverId, subject, roles, board));
    }

    private boolean mayCheckOthers(Long actorUserId, Long serverId, Long boardId) {
        if (evaluation.isAllowed(serverId, null, actorUserId, "MANAGE_SERVER_PERMISSIONS")) {
            return true;
        }
        return boardId != null && evaluation.isAllowed(serverId, boardId, actorUserId, "EDIT_BOARD_PERMISSIONS");
    }
}
