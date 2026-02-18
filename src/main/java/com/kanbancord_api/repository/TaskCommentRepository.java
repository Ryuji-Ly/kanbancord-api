package com.kanbancord_api.repository;

import com.kanbancord_api.model.TaskComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskCommentRepository extends JpaRepository<TaskComment, Long> {

    List<TaskComment> findByTask_TaskId(Long taskId);

    Page<TaskComment> findByTask_TaskId(Long taskId, Pageable pageable);

    List<TaskComment> findByTask_TaskIdAndDeletedAtIsNull(Long taskId);

    Page<TaskComment> findByTask_TaskIdAndDeletedAtIsNull(Long taskId, Pageable pageable);

    List<TaskComment> findByUser_UserId(Long userId);

    List<TaskComment> findByReplyTo_CommentId(Long parentCommentId);

    // Scoped query to prevent cross-server access
    @Query("SELECT c FROM TaskComment c WHERE c.commentId = :commentId AND c.task.board.server.serverId = :serverId")
    Optional<TaskComment> findByCommentIdAndServerId(Long commentId, Long serverId);
}
