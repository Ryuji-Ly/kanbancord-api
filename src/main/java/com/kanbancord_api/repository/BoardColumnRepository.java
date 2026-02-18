package com.kanbancord_api.repository;

import com.kanbancord_api.model.BoardColumn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BoardColumnRepository extends JpaRepository<BoardColumn, Long> {

    List<BoardColumn> findByBoard_BoardId(Long boardId);

    List<BoardColumn> findByBoard_BoardIdOrderByPositionAsc(Long boardId);

    // Scoped query to prevent cross-server access
    @Query("SELECT c FROM BoardColumn c WHERE c.columnId = :columnId AND c.board.server.serverId = :serverId")
    Optional<BoardColumn> findByColumnIdAndServerId(Long columnId, Long serverId);
}
