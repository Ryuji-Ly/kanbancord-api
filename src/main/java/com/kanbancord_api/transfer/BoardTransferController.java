package com.kanbancord_api.transfer;

import com.kanbancord_api.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Exporting a board as a file, and importing one as a new board. */
@RestController
@RequestMapping("/api/servers/{serverId}/boards")
@Validated
public class BoardTransferController {

    private final BoardExportQuery exportQuery;
    private final BoardImportCommands importCommands;

    public BoardTransferController(BoardExportQuery exportQuery, BoardImportCommands importCommands) {
        this.exportQuery = exportQuery;
        this.importCommands = importCommands;
    }

    /**
     * The board as a file, with the optional parts asked for ({@code part=labels&part=comments}...);
     * the board, its columns and tasks are always in it.
     */
    @GetMapping("/{boardId}/export")
    public ResponseEntity<BoardExport> exportBoard(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @RequestParam(name = "part", required = false) List<String> parts,
            @CurrentUser Long userId) {
        return ResponseEntity.ok(exportQuery.export(serverId, boardId, userId, parts));
    }

    /** A new board from a board file. */
    @PostMapping("/import")
    public ResponseEntity<BoardImportResult> importBoard(
            @PathVariable Long serverId,
            @Valid @RequestBody BoardImportRequest request,
            @CurrentUser Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(importCommands.importBoard(serverId, userId, request));
    }
}
