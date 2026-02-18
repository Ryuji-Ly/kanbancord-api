package com.kanbancord_api.repository;

import com.kanbancord_api.model.Board;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BoardRepository extends JpaRepository<Board, Long> {

    List<Board> findByServer_ServerId(Long serverId);

    Page<Board> findByServer_ServerId(Long serverId, Pageable pageable);

    List<Board> findByServer_ServerIdAndIsArchived(Long serverId, Boolean isArchived);

    Page<Board> findByServer_ServerIdAndIsArchived(Long serverId, Boolean isArchived, Pageable pageable);

    List<Board> findByCreatedBy_UserId(Long userId);

    // Scoped query to prevent cross-server access
    Optional<Board> findByBoardIdAndServer_ServerId(Long boardId, Long serverId);
}
