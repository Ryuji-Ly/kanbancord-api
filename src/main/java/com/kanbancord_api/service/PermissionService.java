package com.kanbancord_api.service;

import com.kanbancord_api.model.Permission;
import com.kanbancord_api.repository.PermissionRepository;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class PermissionService {

    private final PermissionRepository permissionRepository;

    public PermissionService(PermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }

    public Permission create(Permission permission) {
        return withKanbanPermissionLoaded(permissionRepository.save(permission));
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
        permissionRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return permissionRepository.existsById(id);
    }
}
