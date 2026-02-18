package com.kanbancord_api.repository;

import com.kanbancord_api.model.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {

    List<Permission> findByScopeTypeAndScopeId(String scopeType, Long scopeId);

    List<Permission> findBySubjectTypeAndSubjectId(String subjectType, Long subjectId);

    List<Permission> findByScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
            String scopeType, Long scopeId, String subjectType, Long subjectId);
}
