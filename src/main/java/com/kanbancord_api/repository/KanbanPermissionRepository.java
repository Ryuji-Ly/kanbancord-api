package com.kanbancord_api.repository;

import com.kanbancord_api.model.KanbanPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KanbanPermissionRepository extends JpaRepository<KanbanPermission, Integer> {

    Optional<KanbanPermission> findByKey(String key);

    List<KanbanPermission> findByCategory(String category);
}
