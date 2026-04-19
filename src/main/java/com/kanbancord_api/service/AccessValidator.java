package com.kanbancord_api.service;

import com.kanbancord_api.config.InternalSyncProperties;
import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.BadRequestException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AccessValidator {

    private final ServerAccessValidator serverAccessValidator;
    private final InternalSyncProperties internalSyncProperties;

    public AccessValidator(
            ServerAccessValidator serverAccessValidator,
            InternalSyncProperties internalSyncProperties) {
        this.serverAccessValidator = serverAccessValidator;
        this.internalSyncProperties = internalSyncProperties;
    }

    public void requireUserInServer(Long userId, Long serverId) {
        Long effectiveUserId = validateAndResolveUserId(userId, "userId");
        serverAccessValidator.validateUserInServer(effectiveUserId, serverId);
    }

    public void requireServerPermission(Long userId, Long serverId, String permissionKey) {
        Long effectiveUserId = validateAndResolveUserId(userId, "userId");
        serverAccessValidator.validateUserHasRole(effectiveUserId, serverId, permissionKey);
    }

    public Long requireAuthenticatedUserId() {
        return getAuthenticatedUserId()
                .orElseThrow(() -> new AccessDeniedException("Authentication required"));
    }

    public void requireSelf(Long requestingUserId, Long targetUserId) {
        Long effectiveUserId = validateAndResolveUserId(requestingUserId, "requestingUserId");

        if (!effectiveUserId.equals(targetUserId)) {
            throw new AccessDeniedException("You can only access your own resources");
        }
    }

    public void requireInternalSyncAccess(String providedBotToken) {
        String internalSyncBotToken = internalSyncProperties.getBotToken();

        if (internalSyncBotToken == null || internalSyncBotToken.isBlank()) {
            throw new AccessDeniedException("Internal sync token is not configured");
        }

        if (providedBotToken == null || providedBotToken.isBlank() || !internalSyncBotToken.equals(providedBotToken)) {
            throw new AccessDeniedException("Invalid internal sync bot token");
        }
    }

    private Long validateAndResolveUserId(Long providedUserId, String fieldName) {
        Optional<Long> authenticatedUserId = getAuthenticatedUserId();
        if (authenticatedUserId.isPresent()) {
            Long authUserId = authenticatedUserId.get();
            if (providedUserId != null && !providedUserId.equals(authUserId)) {
                throw new AccessDeniedException("Authenticated user does not match requested userId");
            }
            return authUserId;
        }

        if (providedUserId == null) {
            throw new BadRequestException(fieldName + " is required");
        }
        return providedUserId;
    }

    private Optional<Long> getAuthenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        Object principal = authentication.getPrincipal();
        if (principal == null || "anonymousUser".equals(principal)) {
            return Optional.empty();
        }

        try {
            if (principal instanceof Long userId) {
                return Optional.of(userId);
            }
            if (principal instanceof String value) {
                return Optional.of(Long.parseLong(value));
            }
            return Optional.of(Long.parseLong(authentication.getName()));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }
}
