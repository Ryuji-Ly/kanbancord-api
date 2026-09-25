package com.kanbancord_api.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRoleAssignmentRepository extends JpaRepository<TaskRoleAssignment, Long> {

    @Query("select a from TaskRoleAssignment a, Task t where t.taskId = a.taskId and t.board.boardId = :boardId")
    List<TaskRoleAssignment> findByBoardId(@Param("boardId") Long boardId);

    boolean existsByTaskIdAndRoleId(Long taskId, Long roleId);
}
