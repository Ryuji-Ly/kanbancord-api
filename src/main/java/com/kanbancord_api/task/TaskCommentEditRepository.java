package com.kanbancord_api.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskCommentEditRepository extends JpaRepository<TaskCommentEdit, Long> {
}
