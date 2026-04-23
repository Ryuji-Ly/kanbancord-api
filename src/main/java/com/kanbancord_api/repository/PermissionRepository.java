package com.kanbancord_api.repository;

import com.kanbancord_api.model.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {

        @Query("SELECT p FROM Permission p LEFT JOIN FETCH p.kanbanPermission WHERE p.scopeType = :scopeType AND p.scopeId = :scopeId")
        List<Permission> findByScopeTypeAndScopeId(String scopeType, Long scopeId);

        @Query("SELECT p FROM Permission p LEFT JOIN FETCH p.kanbanPermission WHERE p.subjectType = :subjectType AND p.subjectId = :subjectId")
        List<Permission> findBySubjectTypeAndSubjectId(String subjectType, Long subjectId);

        @Query("SELECT p FROM Permission p LEFT JOIN FETCH p.kanbanPermission WHERE p.id = :id")
        Optional<Permission> findByIdWithKanbanPermission(Long id);

        List<Permission> findByScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                        String scopeType, Long scopeId, String subjectType, Long subjectId);

        @Query("SELECT p FROM Permission p LEFT JOIN FETCH p.kanbanPermission WHERE p.scopeType = :scopeType AND p.scopeId = :scopeId ORDER BY p.priority DESC, p.id DESC")
        List<Permission> findByScopeTypeAndScopeIdOrderByPriorityDescIdDesc(String scopeType, Long scopeId);

        List<Permission> findByScopeTypeAndScopeIdAndSubjectTypeAndSubjectIdOrderByPriorityDescIdDesc(
                        String scopeType, Long scopeId, String subjectType, Long subjectId);

        Optional<Permission> findByScopeTypeAndScopeIdAndSubjectTypeAndSubjectIdAndKanbanPermission_PermissionId(
                        String scopeType,
                        Long scopeId,
                        String subjectType,
                        Long subjectId,
                        Integer kanbanPermissionId);
}
