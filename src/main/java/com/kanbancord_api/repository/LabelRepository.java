package com.kanbancord_api.repository;

import com.kanbancord_api.model.Label;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LabelRepository extends JpaRepository<Label, Long> {

    List<Label> findByBoard_BoardId(Long boardId);

    // Scoped query to prevent cross-server access
    @Query("SELECT l FROM Label l WHERE l.labelId = :labelId AND l.board.server.serverId = :serverId")
    Optional<Label> findByLabelIdAndServerId(Long labelId, Long serverId);
}
