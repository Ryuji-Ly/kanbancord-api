package com.kanbancord_api.service;

import com.kanbancord_api.model.TaskComment;
import com.kanbancord_api.repository.TaskCommentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class TaskCommentService {

    private final TaskCommentRepository taskCommentRepository;

    public TaskCommentService(TaskCommentRepository taskCommentRepository) {
        this.taskCommentRepository = taskCommentRepository;
    }

    public TaskComment create(TaskComment comment) {
        return taskCommentRepository.save(comment);
    }

    @Transactional(readOnly = true)
    public Optional<TaskComment> findById(Long id) {
        return taskCommentRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<TaskComment> findByIdAndServerId(Long commentId, Long serverId) {
        return taskCommentRepository.findByCommentIdAndServerId(commentId, serverId);
    }

    @Transactional(readOnly = true)
    public List<TaskComment> findAll() {
        return taskCommentRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<TaskComment> findByTaskId(Long taskId) {
        return taskCommentRepository.findByTask_TaskId(taskId);
    }

    @Transactional(readOnly = true)
    public Page<TaskComment> findByTaskId(Long taskId, Pageable pageable) {
        return taskCommentRepository.findByTask_TaskId(taskId, pageable);
    }

    @Transactional(readOnly = true)
    public List<TaskComment> findActiveByTaskId(Long taskId) {
        return taskCommentRepository.findByTask_TaskIdAndDeletedAtIsNull(taskId);
    }

    @Transactional(readOnly = true)
    public Page<TaskComment> findActiveByTaskId(Long taskId, Pageable pageable) {
        return taskCommentRepository.findByTask_TaskIdAndDeletedAtIsNull(taskId, pageable);
    }

    @Transactional(readOnly = true)
    public List<TaskComment> findByUserId(Long userId) {
        return taskCommentRepository.findByUser_UserId(userId);
    }

    @Transactional(readOnly = true)
    public List<TaskComment> findReplies(Long parentCommentId) {
        return taskCommentRepository.findByReplyTo_CommentId(parentCommentId);
    }

    public TaskComment update(TaskComment comment) {
        return taskCommentRepository.save(comment);
    }

    public void deleteById(Long id) {
        taskCommentRepository.deleteById(id);
    }

    public void softDelete(Long id) {
        Optional<TaskComment> comment = taskCommentRepository.findById(id);
        comment.ifPresent(c -> {
            c.setDeletedAt(LocalDateTime.now());
            taskCommentRepository.save(c);
        });
    }

    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return taskCommentRepository.existsById(id);
    }
}
