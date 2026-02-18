package com.kanbancord_api.service;

import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Board;
import com.kanbancord_api.model.BoardColumn;
import com.kanbancord_api.model.Label;
import com.kanbancord_api.model.Task;
import com.kanbancord_api.repository.BoardColumnRepository;
import com.kanbancord_api.repository.BoardRepository;
import com.kanbancord_api.repository.LabelRepository;
import com.kanbancord_api.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BusinessValidationService {

    private final BoardRepository boardRepository;
    private final BoardColumnRepository boardColumnRepository;
    private final TaskRepository taskRepository;
    private final LabelRepository labelRepository;

    public BusinessValidationService(
            BoardRepository boardRepository,
            BoardColumnRepository boardColumnRepository,
            TaskRepository taskRepository,
            LabelRepository labelRepository) {
        this.boardRepository = boardRepository;
        this.boardColumnRepository = boardColumnRepository;
        this.taskRepository = taskRepository;
        this.labelRepository = labelRepository;
    }

    /**
     * Validates that a column belongs to the specified board
     */
    public void validateColumnBelongsToBoard(Long columnId, Long boardId) {
        BoardColumn column = boardColumnRepository.findById(columnId)
                .orElseThrow(() -> new ResourceNotFoundException("BoardColumn", "columnId", columnId));

        if (!column.getBoard().getBoardId().equals(boardId)) {
            throw new BadRequestException(
                    "Column ID " + columnId + " does not belong to board ID " + boardId);
        }
    }

    /**
     * Validates that a task belongs to the specified board
     */
    public void validateTaskBelongsToBoard(Long taskId, Long boardId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", "taskId", taskId));

        if (!task.getBoard().getBoardId().equals(boardId)) {
            throw new BadRequestException(
                    "Task ID " + taskId + " does not belong to board ID " + boardId);
        }
    }

    /**
     * Validates that a label belongs to the specified board
     */
    public void validateLabelBelongsToBoard(Long labelId, Long boardId) {
        Label label = labelRepository.findById(labelId)
                .orElseThrow(() -> new ResourceNotFoundException("Label", "labelId", labelId));

        if (!label.getBoard().getBoardId().equals(boardId)) {
            throw new BadRequestException(
                    "Label ID " + labelId + " does not belong to board ID " + boardId);
        }
    }

    /**
     * Validates that a task is not archived before modification
     */
    public void validateTaskNotArchived(Task task) {
        if (task.getIsArchived() != null && task.getIsArchived()) {
            throw new IllegalStateException("Cannot modify an archived task");
        }
    }

    /**
     * Validates that a board belongs to the specified server
     */
    public void validateBoardBelongsToServer(Long boardId, Long serverId) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new ResourceNotFoundException("Board", "boardId", boardId));

        if (!board.getServer().getServerId().equals(serverId)) {
            throw new BadRequestException(
                    "Board ID " + boardId + " does not belong to server ID " + serverId);
        }
    }

    /**
     * Validates that a column has no tasks before deletion
     */
    public void validateColumnHasNoTasks(Long columnId) {
        long taskCount = taskRepository.findByColumn_ColumnId(columnId).size();
        if (taskCount > 0) {
            throw new IllegalStateException(
                    "Cannot delete column with " + taskCount + " tasks. Move or delete tasks first.");
        }
    }

    /**
     * Validates board name uniqueness within a server
     */
    public void validateBoardNameUnique(String name, Long serverId, Long excludeBoardId) {
        boardRepository.findByServer_ServerId(serverId).stream()
                .filter(board -> !board.getBoardId().equals(excludeBoardId))
                .filter(board -> board.getName().equalsIgnoreCase(name))
                .findFirst()
                .ifPresent(board -> {
                    throw new IllegalStateException(
                            "A board with name '" + name + "' already exists in this server");
                });
    }

    /**
     * Validates label name uniqueness within a board
     */
    public void validateLabelNameUnique(String name, Long boardId, Long excludeLabelId) {
        labelRepository.findByBoard_BoardId(boardId).stream()
                .filter(label -> excludeLabelId == null || !label.getLabelId().equals(excludeLabelId))
                .filter(label -> label.getName().equalsIgnoreCase(name))
                .findFirst()
                .ifPresent(label -> {
                    throw new IllegalStateException(
                            "A label with name '" + name + "' already exists in this board");
                });
    }

    /**
     * Validates that a task can be moved to a target column
     */
    public void validateTaskMove(Task task, Long targetColumnId) {
        BoardColumn targetColumn = boardColumnRepository.findById(targetColumnId)
                .orElseThrow(() -> new ResourceNotFoundException("BoardColumn", "columnId", targetColumnId));

        // Ensure target column belongs to the same board
        if (!targetColumn.getBoard().getBoardId().equals(task.getBoard().getBoardId())) {
            throw new BadRequestException(
                    "Cannot move task to a column from a different board");
        }

        // Ensure task is not archived
        validateTaskNotArchived(task);
    }
}
