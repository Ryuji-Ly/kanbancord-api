package com.kanbancord_api.service;

import com.kanbancord_api.model.KanbanPermission;
import com.kanbancord_api.repository.KanbanPermissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class KanbanPermissionService {

    private final KanbanPermissionRepository kanbanPermissionRepository;

    public KanbanPermissionService(KanbanPermissionRepository kanbanPermissionRepository) {
        this.kanbanPermissionRepository = kanbanPermissionRepository;
    }

    public KanbanPermission create(KanbanPermission permission) {
        return kanbanPermissionRepository.save(permission);
    }

    @Transactional(readOnly = true)
    public Optional<KanbanPermission> findById(Integer id) {
        return kanbanPermissionRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<KanbanPermission> findByKey(String key) {
        return kanbanPermissionRepository.findByKey(key);
    }

    @Transactional(readOnly = true)
    public List<KanbanPermission> findAll() {
        return kanbanPermissionRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<KanbanPermission> findByCategory(String category) {
        return kanbanPermissionRepository.findByCategory(category);
    }

    public KanbanPermission update(KanbanPermission permission) {
        if (permission.getIsSystem() != null && permission.getIsSystem()) {
            throw new IllegalStateException("System kanban permissions cannot be modified");
        }
        return kanbanPermissionRepository.save(permission);
    }

    public void deleteById(Integer id) {
        KanbanPermission permission = kanbanPermissionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("KanbanPermission not found: " + id));
        if (permission.getIsSystem() != null && permission.getIsSystem()) {
            throw new IllegalStateException("System kanban permissions cannot be deleted");
        }
        kanbanPermissionRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean existsById(Integer id) {
        return kanbanPermissionRepository.existsById(id);
    }
}
