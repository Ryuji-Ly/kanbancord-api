package com.kanbancord_api.controller;

import com.kanbancord_api.dto.AuditLogRequest;
import com.kanbancord_api.dto.AuditLogResponse;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.AuditLog;
import com.kanbancord_api.model.Board;
import com.kanbancord_api.model.Server;
import com.kanbancord_api.model.User;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.AuditLogService;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.service.ServerService;
import com.kanbancord_api.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/servers/{serverId}/audit-logs")
@Validated
public class AuditLogController {

    private final AuditLogService auditLogService;
    private final ServerService serverService;
    private final UserService userService;
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;

    public AuditLogController(
            AuditLogService auditLogService,
            ServerService serverService,
            UserService userService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator) {
        this.auditLogService = auditLogService;
        this.serverService = serverService;
        this.userService = userService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
    }

    @PostMapping
    public ResponseEntity<AuditLogResponse> createAuditLog(
            @PathVariable Long serverId,
            @RequestParam Long userId,
            @Valid @RequestBody AuditLogRequest request) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.validatePathMatchesRequestId("serverId", serverId, request.getServerId());
        resourceValidator.validatePermissionSubjectBelongsToServer("USER", request.getUserId(), serverId);

        Server server = serverService.findById(serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Server", "serverId", serverId));
        User actor = userService.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", request.getUserId()));

        Board board = null;
        if (request.getBoardId() != null) {
            board = resourceValidator.requireBoardInServer(request.getBoardId(), serverId);
        }

        AuditLog log = new AuditLog();
        log.setServer(server);
        log.setBoard(board);
        log.setUser(actor);
        log.setAction(request.getAction());
        log.setEntityType(request.getEntityType());
        log.setEntityId(request.getEntityId());
        log.setSource(request.getSource());
        log.setChanges(request.getChanges());

        AuditLog created = auditLogService.create(log);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @GetMapping
    public ResponseEntity<List<AuditLogResponse>> getAuditLogs(
            @PathVariable Long serverId,
            @RequestParam Long userId,
            @RequestParam(required = false) Long boardId,
            @RequestParam(required = false) Long actorUserId) {

        accessValidator.requireUserInServer(userId, serverId);

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
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);

        AuditLog log = resourceValidator.requireAuditLogInServer(logId, serverId);
        return ResponseEntity.ok(toResponse(log));
    }

    @PutMapping("/{logId}")
    public ResponseEntity<AuditLogResponse> updateAuditLog(
            @PathVariable Long serverId,
            @PathVariable Long logId,
            @RequestParam Long userId,
            @Valid @RequestBody AuditLogRequest request) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.validatePathMatchesRequestId("serverId", serverId, request.getServerId());
        resourceValidator.validatePermissionSubjectBelongsToServer("USER", request.getUserId(), serverId);

        AuditLog log = resourceValidator.requireAuditLogInServer(logId, serverId);

        User actor = userService.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", request.getUserId()));

        Board board = null;
        if (request.getBoardId() != null) {
            board = resourceValidator.requireBoardInServer(request.getBoardId(), serverId);
        }

        log.setBoard(board);
        log.setUser(actor);
        log.setAction(request.getAction());
        log.setEntityType(request.getEntityType());
        log.setEntityId(request.getEntityId());
        log.setSource(request.getSource());
        log.setChanges(request.getChanges());

        AuditLog updated = auditLogService.update(log);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{logId}")
    public ResponseEntity<Void> deleteAuditLog(
            @PathVariable Long serverId,
            @PathVariable Long logId,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);

        AuditLog log = resourceValidator.requireAuditLogInServer(logId, serverId);
        auditLogService.deleteById(log.getLogId());

        return ResponseEntity.noContent().build();
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
