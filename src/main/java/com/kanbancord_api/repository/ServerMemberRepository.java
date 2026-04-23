package com.kanbancord_api.repository;

import com.kanbancord_api.model.ServerMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServerMemberRepository extends JpaRepository<ServerMember, Long> {

    List<ServerMember> findByServer_ServerId(Long serverId);

    @Query("SELECT sm FROM ServerMember sm JOIN FETCH sm.user WHERE sm.server.serverId = :serverId")
    List<ServerMember> findByServerIdWithUser(@Param("serverId") Long serverId);

    List<ServerMember> findByUser_UserId(Long userId);

    Optional<ServerMember> findByServer_ServerIdAndUser_UserId(Long serverId, Long userId);

    boolean existsByServer_ServerIdAndUser_UserId(Long serverId, Long userId);
}
