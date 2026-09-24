package com.kanbancord_api.unit.service;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ServerAccessValidator;
import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.UnauthenticatedException;
import com.kanbancord_api.sync.InternalSyncProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AuthorizerTest {

    @Mock
    private ServerAccessValidator serverAccessValidator;

    private Authorizer authorizer;

    @BeforeEach
    void setUp() {
        InternalSyncProperties internalSyncProperties = new InternalSyncProperties();
        internalSyncProperties.setBotToken(sha256Hex("bot-secret"));
        authorizer = new Authorizer(serverAccessValidator, internalSyncProperties);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requireUserInServer_rejectsUnauthenticatedCaller_evenWhenUserIdSupplied() {
        assertThrows(UnauthenticatedException.class, () -> authorizer.requireUserInServer(10L, 20L));
        verifyNoInteractions(serverAccessValidator);
    }

    @Test
    void requireUserInServer_usesAuthenticatedUser() {
        authenticateAs(10L);

        authorizer.requireUserInServer(null, 20L);

        verify(serverAccessValidator).validateUserInServer(10L, 20L);
    }

    @Test
    void requireUserInServer_rejectsMismatchedUserId() {
        authenticateAs(10L);

        assertThrows(AccessDeniedException.class, () -> authorizer.requireUserInServer(11L, 20L));
        verifyNoInteractions(serverAccessValidator);
    }

    @Test
    void requireBoardPermission_delegatesWithBoardScope() {
        authenticateAs(10L);

        authorizer.requireBoardPermission(10L, 20L, 30L, "EDIT_TASK");

        verify(serverAccessValidator).validateUserHasPermission(10L, 20L, 30L, "EDIT_TASK");
    }

    @Test
    void requireServerPermission_delegatesWithoutBoardScope() {
        authenticateAs(10L);

        authorizer.requireServerPermission(10L, 20L, "CREATE_BOARD");

        verify(serverAccessValidator).validateUserHasPermission(10L, 20L, null, "CREATE_BOARD");
    }

    @Test
    void requireAuthenticatedUserId_returnsPrincipal() {
        authenticateAs(42L);

        assertEquals(42L, authorizer.requireAuthenticatedUserId());
    }

    @Test
    void requireSelf_throwsAccessDenied_whenUsersDoNotMatch() {
        authenticateAs(1L);

        assertThrows(AccessDeniedException.class, () -> authorizer.requireSelf(1L, 2L));
    }

    @Test
    void requireSelf_allows_whenUsersMatch() {
        authenticateAs(2L);

        assertDoesNotThrow(() -> authorizer.requireSelf(2L, 2L));
    }

    @Test
    void requireInternalSyncAccess_throwsAccessDenied_whenTokenMissing() {
        assertThrows(AccessDeniedException.class, () -> authorizer.requireInternalSyncAccess(""));
    }

    @Test
    void requireInternalSyncAccess_throwsAccessDenied_whenTokenInvalid() {
        assertThrows(AccessDeniedException.class, () -> authorizer.requireInternalSyncAccess("wrong-token"));
    }

    @Test
    void requireInternalSyncAccess_allows_whenTokenMatches() {
        assertDoesNotThrow(() -> authorizer.requireInternalSyncAccess("bot-secret"));
    }

    private static void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte current : hash) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
