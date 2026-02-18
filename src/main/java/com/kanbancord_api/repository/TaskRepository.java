package com.kanbancord_api.repository;

import com.kanbancord_api.model.Task;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByBoard_BoardId(Long boardId);

    Page<Task> findByBoard_BoardId(Long boardId, Pageable pageable);

    List<Task> findByColumn_ColumnId(Long columnId);

    List<Task> findByColumn_ColumnIdOrderByPositionAsc(Long columnId);

    Page<Task> findByColumn_ColumnIdOrderByPositionAsc(Long columnId, Pageable pageable);

    List<Task> findByBoard_BoardIdAndIsArchived(Long boardId, Boolean isArchived);

    Page<Task> findByBoard_BoardIdAndIsArchived(Long boardId, Boolean isArchived, Pageable pageable);

    List<Task> findByCreatedBy_UserId(Long userId);

    // Scoped query to prevent cross-server access
    @Query("SELECT t FROM Task t WHERE t.taskId = :taskId AND t.board.server.serverId = :serverId")
    Optional<Task> findByTaskIdAndServerId(Long taskId, Long serverId);
}
