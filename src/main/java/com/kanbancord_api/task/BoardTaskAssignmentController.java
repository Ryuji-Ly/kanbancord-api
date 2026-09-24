package com.kanbancord_api.task;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/task-assignments")
@Validated
public class BoardTaskAssignmentController {

    private final TaskAssignmentService taskAssignmentService;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;

    public BoardTaskAssignmentController(
            TaskAssignmentService taskAssignmentService,
            Authorizer authorizer,
            ResourceValidator resourceValidator) {
        this.taskAssignmentService = taskAssignmentService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
    }

    @GetMapping
    public ResponseEntity<List<TaskAssignmentResponse>> getAssignmentsByBoardId(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @CurrentUser Long userId) {

        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_TASK");
        resourceValidator.requireBoardInServer(boardId, serverId);

        List<TaskAssignmentResponse> responses = taskAssignmentService.findByBoardId(boardId).stream()
                .map(TaskAssignmentResponse::from)
                .toList();

        return ResponseEntity.ok(responses);
    }
}
