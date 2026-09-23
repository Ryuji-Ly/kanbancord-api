package com.kanbancord_api.repository;

import com.kanbancord_api.model.Server;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServerRepository extends JpaRepository<Server, Long> {

    List<Server> findDistinctByMembers_User_UserId(Long userId);

    @Query("SELECT s.owner.userId FROM Server s WHERE s.serverId = :serverId")
    Optional<Long> findOwnerIdByServerId(@Param("serverId") Long serverId);

    boolean existsByServerId(Long serverId);
}
