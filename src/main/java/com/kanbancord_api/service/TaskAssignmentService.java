package com.kanbancord_api.service;

import com.kanbancord_api.model.TaskAssignment;
import com.kanbancord_api.repository.TaskAssignmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class TaskAssignmentService {

    private final TaskAssignmentRepository taskAssignmentRepository;

    public TaskAssignmentService(TaskAssignmentRepository taskAssignmentRepository) {
        this.taskAssignmentRepository = taskAssignmentRepository;
    }

    public TaskAssignment create(TaskAssignment taskAssignment) {
        return taskAssignmentRepository.save(taskAssignment);
    }

    @Transactional(readOnly = true)
    public Optional<TaskAssignment> findById(Long id) {
        return taskAssignmentRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<TaskAssignment> findByIdAndServerId(Long assignmentId, Long serverId) {
        return taskAssignmentRepository.findByIdAndServerId(assignmentId, serverId);
    }

    @Transactional(readOnly = true)
    public List<TaskAssignment> findAll() {
        return taskAssignmentRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<TaskAssignment> findByTaskId(Long taskId) {
        return taskAssignmentRepository.findByTask_TaskId(taskId);
    }

    @Transactional(readOnly = true)
    public List<TaskAssignment> findByUserId(Long userId) {
        return taskAssignmentRepository.findByUser_UserId(userId);
    }

    @Transactional(readOnly = true)
    public Optional<TaskAssignment> findByTaskIdAndUserId(Long taskId, Long userId) {
        return taskAssignmentRepository.findByTask_TaskIdAndUser_UserId(taskId, userId);
    }

    public TaskAssignment update(TaskAssignment taskAssignment) {
        return taskAssignmentRepository.save(taskAssignment);
    }

    public void deleteById(Long id) {
        taskAssignmentRepository.deleteById(id);
    }

    public void deleteByTaskIdAndUserId(Long taskId, Long userId) {
        taskAssignmentRepository.deleteByTask_TaskIdAndUser_UserId(taskId, userId);
    }
}
