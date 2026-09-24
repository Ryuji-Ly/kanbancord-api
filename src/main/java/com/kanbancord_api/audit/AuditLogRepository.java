package com.kanbancord_api.audit;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByServer_ServerId(Long serverId);

    /**
     * A server's entries, newest first, with the actor and board loaded. Every filter is optional:
     * a null id or {@code filterEntityTypes = false} leaves it out. {@code beforeLogId} continues
     * from the last entry of the previous page.
     */
    @Query("""
            select a from AuditLog a
            left join fetch a.user u
            left join fetch a.board b
            where a.server.serverId = :serverId
              and (:boardId is null or b.boardId = :boardId)
              and (:actorUserId is null or u.userId = :actorUserId)
              and (:filterEntityTypes = false or a.entityType in :entityTypes)
              and (:beforeLogId is null or a.logId < :beforeLogId)
            order by a.logId desc
            """)
    List<AuditLog> search(
            @Param("serverId") Long serverId,
            @Param("boardId") Long boardId,
            @Param("actorUserId") Long actorUserId,
            @Param("filterEntityTypes") boolean filterEntityTypes,
            @Param("entityTypes") Collection<String> entityTypes,
            @Param("beforeLogId") Long beforeLogId,
            Limit limit);
}
