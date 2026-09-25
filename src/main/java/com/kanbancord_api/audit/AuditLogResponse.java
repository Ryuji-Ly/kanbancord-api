package com.kanbancord_api.audit;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One audit entry, with the names needed to show it: who did it and on which board.
 *
 * @param userId    the actor, or null for changes made by the system
 * @param boardName the board's current name, or null for server-level entries and deleted boards
 *                  (whose name stays in {@code changes})
 */
public record AuditLogResponse(
        Long logId,
        @JsonSerialize(using = ToStringSerializer.class) Long serverId,
        Long boardId,
        String boardName,
        @JsonSerialize(using = ToStringSerializer.class) Long userId,
        String actorUsername,
        String actorDisplayName,
        String actorAvatarUrl,
        String action,
        String entityType,
        Long entityId,
        String source,
        Map<String, Object> changes,
        LocalDateTime createdAt) {

    public static AuditLogResponse from(AuditLog log) {
        var user = log.getUser();
        var board = log.getBoard();
        return new AuditLogResponse(
                log.getLogId(),
                log.getServer() != null ? log.getServer().getServerId() : null,
                board != null ? board.getBoardId() : null,
                board != null ? board.getName() : null,
                user != null ? user.getUserId() : null,
                user != null ? user.getUsername() : null,
                user != null && user.getGlobalName() != null ? user.getGlobalName() : user != null ? user.getUsername() : null,
                user != null ? user.getAvatarUrl() : null,
                log.getAction(),
                log.getEntityType(),
                log.getEntityId(),
                log.getSource(),
                log.getChanges(),
                log.getCreatedAt());
    }
}
