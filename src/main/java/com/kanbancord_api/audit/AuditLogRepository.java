package com.kanbancord_api.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByServer_ServerId(Long serverId);

    List<AuditLog> findByBoard_BoardId(Long boardId);

    List<AuditLog> findByUser_UserId(Long userId);

    List<AuditLog> findByServer_ServerIdOrderByCreatedAtDesc(Long serverId);
}
