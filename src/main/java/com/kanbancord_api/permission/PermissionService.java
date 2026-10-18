package com.kanbancord_api.permission;

import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class PermissionService {

    private final PermissionRepository permissionRepository;
    private final ServerRulesCache serverRulesCache;

    public PermissionService(PermissionRepository permissionRepository, ServerRulesCache serverRulesCache) {
        this.permissionRepository = permissionRepository;
        this.serverRulesCache = serverRulesCache;
    }

    public Permission create(Permission permission) {
        changing(permission);
        return withKanbanPermissionLoaded(permissionRepository.save(permission));
    }

    /** Server rules are cached between requests; a change to one clears its server's once committed. */
    private void changing(Permission permission) {
        if ("SERVER".equals(permission.getScopeType())) {
            serverRulesCache.clearAfterCommit(permission.getScopeId());
        }
    }

    @Transactional(readOnly = true)
    public Optional<Permission> findById(Long id) {
        return permissionRepository.findByIdWithKanbanPermission(id);
    }

    @Transactional(readOnly = true)
    public List<Permission> findAllInServer(Long serverId) {
        return permissionRepository.findAllInServer(serverId);
    }

    @Transactional(readOnly = true)
    public List<Permission> findByScope(String scopeType, Long scopeId) {
        return permissionRepository.findByScopeTypeAndScopeId(scopeType, scopeId);
    }

    public Permission update(Permission permission) {
        changing(permission);
        return withKanbanPermissionLoaded(permissionRepository.saveAndFlush(permission));
    }

    /**
     * Saving a detached rule merges it, and the returned copy holds a lazy reference to its catalog
     * entry. Load it while the transaction is open so callers can map the result (open-in-view is off).
     */
    private static Permission withKanbanPermissionLoaded(Permission saved) {
        Hibernate.initialize(saved.getKanbanPermission());
        return saved;
    }

    public void deleteById(Long id) {
        permissionRepository.findById(id).ifPresent(this::changing);
        permissionRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return permissionRepository.existsById(id);
    }
}
