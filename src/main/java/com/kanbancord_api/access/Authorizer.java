package com.kanbancord_api.access;

import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.UnauthenticatedException;
import com.kanbancord_api.security.CurrentUserArgumentResolver;
import com.kanbancord_api.sync.InternalSyncProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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

        if (providedBotToken == null || providedBotToken.isBlank()) {
            throw new AccessDeniedException("Invalid internal sync bot token");
        }

        byte[] expectedHashBytes = decodeSha256Hex(expectedBotTokenHash);
        byte[] providedHashBytes = sha256(providedBotToken);

        if (!MessageDigest.isEqual(expectedHashBytes, providedHashBytes)) {
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

    private byte[] decodeSha256Hex(String value) {
        String normalized = value.trim();
        if (!normalized.matches("(?i)^[0-9a-f]{64}$")) {
            throw new AccessDeniedException("Internal sync token hash must be a 64-character SHA-256 hex value");
        }

        byte[] bytes = new byte[32];
        for (int index = 0; index < normalized.length(); index += 2) {
            bytes[index / 2] = (byte) Integer.parseInt(normalized.substring(index, index + 2), 16);
        }
        return bytes;
    }

    private byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is not available", ex);
        }
    }
}
