package com.kanbancord_api.server;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /** Whether the server has open permissions on; empty for a server that is not known. */
    @Query(value = "SELECT open_permissions FROM servers WHERE server_id = :serverId", nativeQuery = true)
    Optional<Boolean> findOpenPermissions(@Param("serverId") Long serverId);

    @Modifying
    @Query(value = "UPDATE servers SET open_permissions = :open WHERE server_id = :serverId", nativeQuery = true)
    int setOpenPermissions(@Param("serverId") Long serverId, @Param("open") boolean open);
}
