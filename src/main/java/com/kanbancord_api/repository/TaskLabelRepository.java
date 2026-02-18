package com.kanbancord_api.repository;

import com.kanbancord_api.model.TaskLabel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskLabelRepository extends JpaRepository<TaskLabel, Long> {

    List<TaskLabel> findByTask_TaskId(Long taskId);

    List<TaskLabel> findByLabel_LabelId(Long labelId);

    Optional<TaskLabel> findByTask_TaskIdAndLabel_LabelId(Long taskId, Long labelId);

    void deleteByTask_TaskIdAndLabel_LabelId(Long taskId, Long labelId);

    // Scoped query to prevent cross-server access
    @Query("SELECT tl FROM TaskLabel tl WHERE tl.id = :taskLabelId AND tl.task.board.server.serverId = :serverId")
    Optional<TaskLabel> findByIdAndServerId(Long taskLabelId, Long serverId);
}
