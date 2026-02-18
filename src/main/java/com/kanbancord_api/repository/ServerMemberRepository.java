package com.kanbancord_api.repository;

import com.kanbancord_api.model.ServerMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServerMemberRepository extends JpaRepository<ServerMember, Long> {

    List<ServerMember> findByServer_ServerId(Long serverId);

    List<ServerMember> findByUser_UserId(Long userId);

    Optional<ServerMember> findByServer_ServerIdAndUser_UserId(Long serverId, Long userId);

    boolean existsByServer_ServerIdAndUser_UserId(Long serverId, Long userId);
}
