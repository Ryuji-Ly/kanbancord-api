package com.kanbancord_api.service;

import com.kanbancord_api.model.Task;
import com.kanbancord_api.repository.TaskRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class TaskService {

    private final TaskRepository taskRepository;

    public TaskService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    public Task create(Task task) {
        return taskRepository.save(task);
    }

    @Transactional(readOnly = true)
    public Optional<Task> findById(Long id) {
        return taskRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Task> findAll() {
        return taskRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Task> findByBoardId(Long boardId) {
        return taskRepository.findByBoard_BoardId(boardId);
    }

    @Transactional(readOnly = true)
    public Page<Task> findByBoardId(Long boardId, Pageable pageable) {
        return taskRepository.findByBoard_BoardId(boardId, pageable);
    }

    @Transactional(readOnly = true)
    public List<Task> findByColumnId(Long columnId) {
        return taskRepository.findByColumn_ColumnId(columnId);
    }

    @Transactional(readOnly = true)
    public List<Task> findByColumnIdOrdered(Long columnId) {
        return taskRepository.findByColumn_ColumnIdOrderByPositionAsc(columnId);
    }

    @Transactional(readOnly = true)
    public Page<Task> findByColumnIdOrdered(Long columnId, Pageable pageable) {
        return taskRepository.findByColumn_ColumnIdOrderByPositionAsc(columnId, pageable);
    }

    @Transactional(readOnly = true)
    public List<Task> findByBoardIdAndArchived(Long boardId, Boolean isArchived) {
        return taskRepository.findByBoard_BoardIdAndIsArchived(boardId, isArchived);
    }

    @Transactional(readOnly = true)
    public Page<Task> findByBoardIdAndArchived(Long boardId, Boolean isArchived, Pageable pageable) {
        return taskRepository.findByBoard_BoardIdAndIsArchived(boardId, isArchived, pageable);
    }

    @Transactional(readOnly = true)
    public List<Task> findByCreatedBy(Long userId) {
        return taskRepository.findByCreatedBy_UserId(userId);
    }

    @Transactional(readOnly = true)
    public Optional<Task> findByIdAndServerId(Long taskId, Long serverId) {
        return taskRepository.findByTaskIdAndServerId(taskId, serverId);
    }

    public Task update(Task task) {
        return taskRepository.save(task);
    }

    public void deleteById(Long id) {
        taskRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return taskRepository.existsById(id);
    }
}
