package com.kanbancord_api.label;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/labels")
@Validated
public class LabelController {

    private final LabelService labelService;
    private final LabelCommands labelCommands;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;

    public LabelController(
            LabelService labelService,
            LabelCommands labelCommands,
            Authorizer authorizer,
            ResourceValidator resourceValidator) {
        this.labelService = labelService;
        this.labelCommands = labelCommands;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
    }

    @PostMapping
    public ResponseEntity<LabelResponse> createLabel(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @Valid @RequestBody LabelRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(labelCommands.create(serverId, boardId, userId, request));
    }

    @GetMapping
    public ResponseEntity<List<LabelResponse>> getAllLabels(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @CurrentUser Long userId) {

        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_BOARD");

        resourceValidator.requireBoardInServer(boardId, serverId);

        List<Label> labels = labelService.findByBoardId(boardId);
        List<LabelResponse> responses = labels.stream()
                .map(LabelResponse::from)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{labelId}")
    public ResponseEntity<LabelResponse> getLabelById(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long labelId,
            @CurrentUser Long userId) {

        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_BOARD");
        resourceValidator.requireBoardInServer(boardId, serverId);
        resourceValidator.validateLabelBelongsToBoard(labelId, boardId);

        Label label = resourceValidator.requireLabelInServer(labelId, serverId);

        return ResponseEntity.ok(LabelResponse.from(label));
    }

    @PutMapping("/{labelId}")
    public ResponseEntity<LabelResponse> updateLabel(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long labelId,
            @Valid @RequestBody LabelRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.ok(labelCommands.update(serverId, boardId, labelId, userId, request));
    }

    @DeleteMapping("/{labelId}")
    public ResponseEntity<Void> deleteLabel(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long labelId,
            @CurrentUser Long userId) {
        labelCommands.delete(serverId, boardId, labelId, userId);
        return ResponseEntity.noContent().build();
    }
}
