package com.kanbancord_api.priority;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BoardPriorityRepository extends JpaRepository<BoardPriority, Long> {

    List<BoardPriority> findByBoardIdOrderByPositionAsc(Long boardId);

    Optional<BoardPriority> findByBoardIdAndNameIgnoreCase(Long boardId, String name);
}
