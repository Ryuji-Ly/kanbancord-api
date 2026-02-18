package com.kanbancord_api.service;

import com.kanbancord_api.model.AuditLog;
import com.kanbancord_api.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public AuditLog create(AuditLog auditLog) {
        return auditLogRepository.save(auditLog);
    }

    @Transactional(readOnly = true)
    public Optional<AuditLog> findById(Long id) {
        return auditLogRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> findAll() {
        return auditLogRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<AuditLog> findByServerId(Long serverId) {
        return auditLogRepository.findByServer_ServerId(serverId);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> findByServerIdOrdered(Long serverId) {
        return auditLogRepository.findByServer_ServerIdOrderByCreatedAtDesc(serverId);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> findByBoardId(Long boardId) {
        return auditLogRepository.findByBoard_BoardId(boardId);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> findByUserId(Long userId) {
        return auditLogRepository.findByUser_UserId(userId);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> findByDateRange(LocalDateTime start, LocalDateTime end) {
        return auditLogRepository.findByCreatedAtBetween(start, end);
    }

    public AuditLog update(AuditLog auditLog) {
        return auditLogRepository.save(auditLog);
    }

    public void deleteById(Long id) {
        auditLogRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return auditLogRepository.existsById(id);
    }
}
