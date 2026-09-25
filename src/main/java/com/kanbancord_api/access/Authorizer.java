package com.kanbancord_api.access;

import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.UnauthenticatedException;
import com.kanbancord_api.security.CurrentUserArgumentResolver;
import com.kanbancord_api.sync.InternalBotToken;
import com.kanbancord_api.sync.InternalSyncProperties;
import org.springframework.stereotype.Service;


@Service
public class Authorizer {

    private final ServerAccessValidator serverAccessValidator;
    private final InternalSyncProperties internalSyncProperties;

    public Authorizer(
            ServerAccessValidator serverAccessValidator,
            InternalSyncProperties internalSyncProperties) {
        this.serverAccessValidator = serverAccessValidator;
        this.internalSyncProperties = internalSyncProperties;
    }

    public void requireUserInServer(Long userId, Long serverId) {
        serverAccessValidator.validateUserInServer(requireActor(userId), serverId);
    }

    /**
     * Requires a server-scoped permission. Use {@link #requireBoardPermission} for anything that
     * happens inside a board so board-level overrides are honoured.
     */
    public void requireServerPermission(Long userId, Long serverId, String permissionKey) {
        serverAccessValidator.validateUserHasPermission(requireActor(userId), serverId, null, permissionKey);
    }

    /**
     * Requires a permission evaluated in the context of a board, so board-scoped rules override
     * server-scoped ones. The caller must already have verified that {@code boardId} belongs to
     * {@code serverId}.
     */
    public void requireBoardPermission(Long userId, Long serverId, Long boardId, String permissionKey) {
        serverAccessValidator.validateUserHasPermission(requireActor(userId), serverId, boardId, permissionKey);
    }

    public Long requireAuthenticatedUserId() {
        return CurrentUserArgumentResolver.currentUserId().orElseThrow(UnauthenticatedException::new);
    }

    public void requireSelf(Long requestingUserId, Long targetUserId) {
        if (!requireActor(requestingUserId).equals(targetUserId)) {
            throw new AccessDeniedException("You can only access your own resources");
        }
    }

    public void requireInternalSyncAccess(String providedBotToken) {
        String expectedBotTokenHash = internalSyncProperties.getBotToken();

        if (expectedBotTokenHash == null || expectedBotTokenHash.isBlank()) {
            throw new AccessDeniedException("Internal sync token is not configured");
        }

        if (!InternalBotToken.matches(expectedBotTokenHash, providedBotToken)) {
            throw new AccessDeniedException("Invalid internal sync bot token");
        }
    }

    /**
     * Returns the authenticated user's id. The id passed in by the caller must match it; identity is
     * never taken from the request.
     */
    private Long requireActor(Long claimedUserId) {
        Long authenticatedUserId = requireAuthenticatedUserId();
        if (claimedUserId != null && !claimedUserId.equals(authenticatedUserId)) {
            throw new AccessDeniedException("Authenticated user does not match requested userId");
        }
        return authenticatedUserId;
    }
}
