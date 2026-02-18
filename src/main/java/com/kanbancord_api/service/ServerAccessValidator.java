package com.kanbancord_api.service;

import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.repository.ServerMemberRepository;
import com.kanbancord_api.repository.ServerRepository;
import org.springframework.stereotype.Service;

@Service
public class ServerAccessValidator {

    private final ServerMemberRepository serverMemberRepository;
    private final ServerRepository serverRepository;

    public ServerAccessValidator(ServerMemberRepository serverMemberRepository, ServerRepository serverRepository) {
        this.serverMemberRepository = serverMemberRepository;
        this.serverRepository = serverRepository;
    }

    /**
     * Validates that a user is a member of the specified server.
     * 
     * @throws AccessDeniedException     if user is not a member
     * @throws ResourceNotFoundException if server doesn't exist
     */
    public void validateUserInServer(Long userId, Long serverId) {
        if (!serverRepository.existsById(serverId)) {
            throw new ResourceNotFoundException("Server", "serverId", serverId);
        }

        if (!serverMemberRepository.existsByServer_ServerIdAndUser_UserId(serverId, userId)) {
            throw new AccessDeniedException("You are not a member of this server");
        }
    }

    /**
     * Validates that a user owns the specified server.
     * 
     * @throws AccessDeniedException     if user is not the owner
     * @throws ResourceNotFoundException if server doesn't exist
     */
    public void validateUserOwnsServer(Long userId, Long serverId) {
        var server = serverRepository.findById(serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Server", "serverId", serverId));

        if (!server.getOwner().getUserId().equals(userId)) {
            throw new AccessDeniedException("You are not the owner of this server");
        }
    }

    /**
     * Validates that a user has a specific role/permission in a server.
     * TODO: Implement role-based permission checking when role system is complete
     * For now, this just validates membership.
     */
    public void validateUserHasRole(Long userId, Long serverId, String requiredPermission) {
        // TODO: Implement role-based permission checking
        // For now, just validate membership
        validateUserInServer(userId, serverId);
    }
}
