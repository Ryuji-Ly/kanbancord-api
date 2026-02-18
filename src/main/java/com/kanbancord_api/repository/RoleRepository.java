package com.kanbancord_api.repository;

import com.kanbancord_api.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    List<Role> findByServer_ServerId(Long serverId);

    List<Role> findByServer_ServerIdOrderByPositionAsc(Long serverId);
}
