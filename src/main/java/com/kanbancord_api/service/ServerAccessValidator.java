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
    private final PermissionEvaluationService permissionEvaluationService;

    public ServerAccessValidator(
            ServerMemberRepository serverMemberRepository,
            ServerRepository serverRepository,
            PermissionEvaluationService permissionEvaluationService) {
        this.serverMemberRepository = serverMemberRepository;
        this.serverRepository = serverRepository;
        this.permissionEvaluationService = permissionEvaluationService;
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
     * Validates that a user has a specific permission in a server.
     */
    public void validateUserHasRole(Long userId, Long serverId, String requiredPermission) {
        validateUserInServer(userId, serverId);

        if (requiredPermission == null || requiredPermission.isBlank()) {
            return;
        }

        if (!permissionEvaluationService.isAllowed(serverId, null, userId, requiredPermission)) {
            throw new AccessDeniedException("Missing required permission: " + requiredPermission);
        }
    }
}
