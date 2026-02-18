package com.kanbancord_api.repository;

import com.kanbancord_api.model.Server;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ServerRepository extends JpaRepository<Server, Long> {

    List<Server> findByOwner_UserId(Long ownerId);

    boolean existsByServerId(Long serverId);
}
