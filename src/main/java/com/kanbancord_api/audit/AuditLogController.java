package com.kanbancord_api.audit;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Read-only access to the audit log. Entries are append-only and written by the
 * server itself, never by clients.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/audit-logs")
@Validated
public class AuditLogController {

    private final AuditLogService auditLogService;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;

    public AuditLogController(
            AuditLogService auditLogService,
            Authorizer authorizer,
            ResourceValidator resourceValidator) {
        this.auditLogService = auditLogService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
    }

    @GetMapping
    public ResponseEntity<List<AuditLogResponse>> getAuditLogs(
            @PathVariable Long serverId,
            @CurrentUser Long userId,
            @RequestParam(required = false) Long boardId,
            @RequestParam(required = false) Long actorUserId) {

        authorizer.requireServerPermission(userId, serverId, "VIEW_AUDIT_LOG");

        List<AuditLog> logs;
        if (boardId != null) {
            resourceValidator.requireBoardInServer(boardId, serverId);
            logs = auditLogService.findByBoardId(boardId);
        } else if (actorUserId != null) {
            resourceValidator.validatePermissionSubjectBelongsToServer("USER", actorUserId, serverId);
            logs = auditLogService.findByUserId(actorUserId).stream()
                    .filter(log -> log.getServer() != null && serverId.equals(log.getServer().getServerId()))
                    .collect(Collectors.toList());
        } else {
            logs = auditLogService.findByServerIdOrdered(serverId);
        }

        List<AuditLogResponse> responses = logs.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{logId}")
    public ResponseEntity<AuditLogResponse> getAuditLogById(
            @PathVariable Long serverId,
            @PathVariable Long logId,
            @CurrentUser Long userId) {

        authorizer.requireServerPermission(userId, serverId, "VIEW_AUDIT_LOG");

        AuditLog log = resourceValidator.requireAuditLogInServer(logId, serverId);
        return ResponseEntity.ok(toResponse(log));
    }

    private AuditLogResponse toResponse(AuditLog log) {
        AuditLogResponse response = new AuditLogResponse();
        response.setLogId(log.getLogId());
        response.setServerId(log.getServer() != null ? log.getServer().getServerId() : null);
        response.setBoardId(log.getBoard() != null ? log.getBoard().getBoardId() : null);
        response.setUserId(log.getUser() != null ? log.getUser().getUserId() : null);
        response.setAction(log.getAction());
        response.setEntityType(log.getEntityType());
        response.setEntityId(log.getEntityId());
        response.setSource(log.getSource());
        response.setChanges(log.getChanges());
        response.setCreatedAt(log.getCreatedAt());
        return response;
    }
}
