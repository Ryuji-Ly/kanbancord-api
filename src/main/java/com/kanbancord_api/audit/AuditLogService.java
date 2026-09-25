package com.kanbancord_api.audit;

import com.kanbancord_api.user.User;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class AuditLogService {

    /** Filters for {@link #search}; null or empty means "any". */
    public record Filter(Long boardId, Long actorUserId, Collection<String> entityTypes) {
    }

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

    /** Up to {@code limit} of the server's entries matching the filter, newest first, older than {@code beforeLogId}. */
    @Transactional(readOnly = true)
    public List<AuditLog> search(Long serverId, Filter filter, Long beforeLogId, int limit) {
        boolean filterEntityTypes = filter.entityTypes() != null && !filter.entityTypes().isEmpty();
        return auditLogRepository.search(
                serverId,
                filter.boardId(),
                filter.actorUserId(),
                filterEntityTypes,
                // An empty IN list is not valid SQL; the list is ignored when not filtering.
                filterEntityTypes ? filter.entityTypes() : List.of(""),
                beforeLogId,
                Limit.of(limit));
    }

    @Transactional(readOnly = true)
    public List<User> actors(Long serverId) {
        return auditLogRepository.findActors(serverId);
    }

    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return auditLogRepository.existsById(id);
    }
}
