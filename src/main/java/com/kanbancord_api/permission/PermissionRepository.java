package com.kanbancord_api.permission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

        @Query("SELECT p FROM Permission p LEFT JOIN FETCH p.kanbanPermission WHERE p.scopeType = :scopeType AND p.scopeId = :scopeId ORDER BY p.priority DESC, p.id DESC")
        List<Permission> findByScopeTypeAndScopeIdOrderByPriorityDescIdDesc(String scopeType, Long scopeId);

        @Query("SELECT p FROM Permission p LEFT JOIN FETCH p.kanbanPermission WHERE p.scopeType = :scopeType AND p.scopeId IN :scopeIds")
        List<Permission> findByScopeTypeAndScopeIdIn(String scopeType, Collection<Long> scopeIds);

        /** The server's own rules and the rules of all its boards. */
        @Query("SELECT p FROM Permission p LEFT JOIN FETCH p.kanbanPermission "
                        + "WHERE (p.scopeType = 'SERVER' AND p.scopeId = :serverId) "
                        + "OR (p.scopeType = 'BOARD' AND p.scopeId IN "
                        + "(SELECT b.boardId FROM Board b WHERE b.server.serverId = :serverId))")
        List<Permission> findAllInServer(Long serverId);

        List<Permission> findByScopeTypeAndScopeIdAndSubjectTypeAndSubjectIdOrderByPriorityDescIdDesc(
                        String scopeType, Long scopeId, String subjectType, Long subjectId);

        Optional<Permission> findByScopeTypeAndScopeIdAndSubjectTypeAndSubjectIdAndKanbanPermission_PermissionId(
                        String scopeType,
                        Long scopeId,
                        String subjectType,
                        Long subjectId,
                        Integer kanbanPermissionId);
}
