package com.kanbancord_api.audit;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    /**
     * The server's entries, newest first, a page at a time. Filters combine: a board, an actor
     * (past members included), and any number of entity types such as TASK or PERMISSION.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<AuditLogPage> getAuditLogs(
            @PathVariable Long serverId,
            @CurrentUser Long userId,
            @RequestParam(required = false) Long boardId,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(name = "entityType", required = false) List<String> entityTypes,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {

        authorizer.requireServerPermission(userId, serverId, "VIEW_AUDIT_LOG");
        if (boardId != null) {
            resourceValidator.requireBoardInServer(boardId, serverId);
        }

        // One extra entry tells whether there is another page.
        List<AuditLog> logs = auditLogService.search(
                serverId, new AuditLogService.Filter(boardId, actorUserId, entityTypes), before, limit + 1);
        boolean more = logs.size() > limit;
        List<AuditLogResponse> entries = logs.stream().limit(limit).map(AuditLogResponse::from).toList();
        Long nextBefore = more ? entries.get(entries.size() - 1).logId() : null;
        return ResponseEntity.ok(new AuditLogPage(entries, nextBefore));
    }

    @GetMapping("/{logId}")
    @Transactional(readOnly = true)
    public ResponseEntity<AuditLogResponse> getAuditLogById(
            @PathVariable Long serverId,
            @PathVariable Long logId,
            @CurrentUser Long userId) {

        authorizer.requireServerPermission(userId, serverId, "VIEW_AUDIT_LOG");

        AuditLog log = resourceValidator.requireAuditLogInServer(logId, serverId);
        return ResponseEntity.ok(AuditLogResponse.from(log));
    }
}
