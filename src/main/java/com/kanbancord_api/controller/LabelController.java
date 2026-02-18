package com.kanbancord_api.controller;

import com.kanbancord_api.dto.LabelRequest;
import com.kanbancord_api.dto.LabelResponse;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Board;
import com.kanbancord_api.model.Label;
import com.kanbancord_api.service.BoardService;
import com.kanbancord_api.service.BusinessValidationService;
import com.kanbancord_api.service.LabelService;
import com.kanbancord_api.service.ServerAccessValidator;
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
    private final BoardService boardService;
    private final ServerAccessValidator accessValidator;
    private final BusinessValidationService businessValidation;

    public LabelController(
            LabelService labelService,
            BoardService boardService,
            ServerAccessValidator accessValidator,
            BusinessValidationService businessValidation) {
        this.labelService = labelService;
        this.boardService = boardService;
        this.accessValidator = accessValidator;
        this.businessValidation = businessValidation;
    }

    @PostMapping
    public ResponseEntity<LabelResponse> createLabel(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @Valid @RequestBody LabelRequest request,
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        Board board = boardService.findByIdAndServerId(boardId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Board", "boardId", boardId));

        // Validate label name is unique within the board
        businessValidation.validateLabelNameUnique(request.getName(), boardId, null);

        Label label = new Label();
        label.setBoard(board);
        label.setName(request.getName());
        label.setColor(request.getColor());

        Label created = labelService.create(label);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(created));
    }

    @GetMapping
    public ResponseEntity<List<LabelResponse>> getAllLabels(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        boardService.findByIdAndServerId(boardId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Board", "boardId", boardId));

        List<Label> labels = labelService.findByBoardId(boardId);
        List<LabelResponse> responses = labels.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{labelId}")
    public ResponseEntity<LabelResponse> getLabelById(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long labelId,
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        Label label = labelService.findByIdAndServerId(labelId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Label", "labelId", labelId));

        return ResponseEntity.ok(mapToResponse(label));
    }

    @PutMapping("/{labelId}")
    public ResponseEntity<LabelResponse> updateLabel(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long labelId,
            @Valid @RequestBody LabelRequest request,
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        Label label = labelService.findByIdAndServerId(labelId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Label", "labelId", labelId));

        // Validate label name is unique within the board (excluding current label)
        businessValidation.validateLabelNameUnique(request.getName(), boardId, labelId);

        label.setName(request.getName());
        label.setColor(request.getColor());

        Label updated = labelService.update(label);
        return ResponseEntity.ok(mapToResponse(updated));
    }

    @DeleteMapping("/{labelId}")
    public ResponseEntity<Void> deleteLabel(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @PathVariable Long labelId,
            @RequestParam(required = false) Long userId) {

        if (userId != null) {
            accessValidator.validateUserInServer(userId, serverId);
        }

        Label label = labelService.findByIdAndServerId(labelId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Label", "labelId", labelId));

        labelService.deleteById(label.getLabelId());
        return ResponseEntity.noContent().build();
    }

    private LabelResponse mapToResponse(Label label) {
        LabelResponse response = new LabelResponse();
        response.setLabelId(label.getLabelId());
        response.setBoardId(label.getBoard().getBoardId());
        response.setName(label.getName());
        response.setColor(label.getColor());
        return response;
    }
}
