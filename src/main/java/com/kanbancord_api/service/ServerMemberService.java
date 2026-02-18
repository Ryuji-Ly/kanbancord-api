package com.kanbancord_api.service;

import com.kanbancord_api.model.ServerMember;
import com.kanbancord_api.repository.ServerMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class ServerMemberService {

    private final ServerMemberRepository serverMemberRepository;

    public ServerMemberService(ServerMemberRepository serverMemberRepository) {
        this.serverMemberRepository = serverMemberRepository;
    }

    public ServerMember create(ServerMember serverMember) {
        return serverMemberRepository.save(serverMember);
    }

    @Transactional(readOnly = true)
    public Optional<ServerMember> findById(Long id) {
        return serverMemberRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<ServerMember> findAll() {
        return serverMemberRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<ServerMember> findByServerId(Long serverId) {
        return serverMemberRepository.findByServer_ServerId(serverId);
    }

    @Transactional(readOnly = true)
    public List<ServerMember> findByUserId(Long userId) {
        return serverMemberRepository.findByUser_UserId(userId);
    }

    @Transactional(readOnly = true)
    public Optional<ServerMember> findByServerIdAndUserId(Long serverId, Long userId) {
        return serverMemberRepository.findByServer_ServerIdAndUser_UserId(serverId, userId);
    }

    public ServerMember update(ServerMember serverMember) {
        return serverMemberRepository.save(serverMember);
    }

    public void deleteById(Long id) {
        serverMemberRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean existsByServerIdAndUserId(Long serverId, Long userId) {
        return serverMemberRepository.existsByServer_ServerIdAndUser_UserId(serverId, userId);
    }
}
