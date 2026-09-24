package com.kanbancord_api.permission;

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

    @Transactional(readOnly = true)
    public boolean existsById(Integer id) {
        return kanbanPermissionRepository.existsById(id);
    }
}
