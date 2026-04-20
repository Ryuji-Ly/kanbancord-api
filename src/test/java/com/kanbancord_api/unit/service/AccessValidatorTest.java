package com.kanbancord_api.unit.service;

import com.kanbancord_api.config.InternalSyncProperties;
import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.ServerAccessValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AccessValidatorTest {

    @Mock
    private ServerAccessValidator serverAccessValidator;

    private AccessValidator accessValidator;

    @BeforeEach
    void setUp() {
        InternalSyncProperties internalSyncProperties = new InternalSyncProperties();
        internalSyncProperties.setBotToken(sha256Hex("bot-secret"));
        accessValidator = new AccessValidator(serverAccessValidator, internalSyncProperties);
    }

    @Test
    void requireUserInServer_throwsBadRequest_whenUserIdIsNull() {
        assertThrows(BadRequestException.class, () -> accessValidator.requireUserInServer(null, 1L));
        verifyNoInteractions(serverAccessValidator);
    }

    @Test
    void requireUserInServer_delegatesToServerValidator_whenInputsAreValid() {
        accessValidator.requireUserInServer(10L, 20L);

        verify(serverAccessValidator).validateUserInServer(10L, 20L);
    }

    @Test
    void requireSelf_throwsBadRequest_whenRequestingUserIdIsNull() {
        assertThrows(BadRequestException.class, () -> accessValidator.requireSelf(null, 1L));
    }

    @Test
    void requireSelf_throwsAccessDenied_whenUsersDoNotMatch() {
        assertThrows(AccessDeniedException.class, () -> accessValidator.requireSelf(1L, 2L));
    }

    @Test
    void requireSelf_allows_whenUsersMatch() {
        assertDoesNotThrow(() -> accessValidator.requireSelf(2L, 2L));
    }

    @Test
    void requireInternalSyncAccess_throwsAccessDenied_whenTokenMissing() {
        assertThrows(AccessDeniedException.class, () -> accessValidator.requireInternalSyncAccess(""));
    }

    @Test
    void requireInternalSyncAccess_throwsAccessDenied_whenTokenInvalid() {
        assertThrows(AccessDeniedException.class, () -> accessValidator.requireInternalSyncAccess("wrong-token"));
    }

    @Test
    void requireInternalSyncAccess_allows_whenTokenMatches() {
        assertDoesNotThrow(() -> accessValidator.requireInternalSyncAccess("bot-secret"));
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
