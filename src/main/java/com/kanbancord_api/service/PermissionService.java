package com.kanbancord_api.service;

import com.kanbancord_api.model.Permission;
import com.kanbancord_api.repository.PermissionRepository;
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
        return permissionRepository.save(permission);
    }

    @Transactional(readOnly = true)
    public Optional<Permission> findById(Long id) {
        return permissionRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Permission> findAll() {
        return permissionRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Permission> findByScope(String scopeType, Long scopeId) {
        return permissionRepository.findByScopeTypeAndScopeId(scopeType, scopeId);
    }

    @Transactional(readOnly = true)
    public List<Permission> findBySubject(String subjectType, Long subjectId) {
        return permissionRepository.findBySubjectTypeAndSubjectId(subjectType, subjectId);
    }

    @Transactional(readOnly = true)
    public List<Permission> findByScopeAndSubject(String scopeType, Long scopeId, String subjectType, Long subjectId) {
        return permissionRepository.findByScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                scopeType, scopeId, subjectType, subjectId);
    }

    public Permission update(Permission permission) {
        return permissionRepository.save(permission);
    }

    public void deleteById(Long id) {
        permissionRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return permissionRepository.existsById(id);
    }
}
