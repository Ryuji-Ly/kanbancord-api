package com.kanbancord_api.repository;

import com.kanbancord_api.model.TaskAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskAssignmentRepository extends JpaRepository<TaskAssignment, Long> {

    List<TaskAssignment> findByTask_TaskId(Long taskId);

    List<TaskAssignment> findByUser_UserId(Long userId);

    Optional<TaskAssignment> findByTask_TaskIdAndUser_UserId(Long taskId, Long userId);

    void deleteByTask_TaskIdAndUser_UserId(Long taskId, Long userId);

    // Scoped query to prevent cross-server access
    @Query("SELECT a FROM TaskAssignment a WHERE a.id = :assignmentId AND a.task.board.server.serverId = :serverId")
    Optional<TaskAssignment> findByIdAndServerId(Long assignmentId, Long serverId);
}
