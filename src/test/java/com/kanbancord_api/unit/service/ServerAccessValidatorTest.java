package com.kanbancord_api.unit.service;

import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.repository.ServerMemberRepository;
import com.kanbancord_api.repository.ServerRepository;
import com.kanbancord_api.service.PermissionEvaluationService;
import com.kanbancord_api.service.ServerAccessValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServerAccessValidatorTest {

    @Mock
    private ServerMemberRepository serverMemberRepository;

    @Mock
    private ServerRepository serverRepository;

    @Mock
    private PermissionEvaluationService permissionEvaluationService;

    private ServerAccessValidator serverAccessValidator;

    @BeforeEach
    void setUp() {
        serverAccessValidator = new ServerAccessValidator(
                serverMemberRepository,
                serverRepository,
                permissionEvaluationService);
    }

    @Test
    void validateUserHasRole_allowsWhenPermissionBlank_afterMembershipCheck() {
        when(serverRepository.existsById(2L)).thenReturn(true);
        when(serverMemberRepository.existsByServer_ServerIdAndUser_UserId(2L, 1L)).thenReturn(true);

        assertDoesNotThrow(() -> serverAccessValidator.validateUserHasRole(1L, 2L, " "));

        verifyNoInteractions(permissionEvaluationService);
    }

    @Test
    void validateUserHasRole_throwsNotFound_whenServerMissing() {
        when(serverRepository.existsById(2L)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class,
                () -> serverAccessValidator.validateUserHasRole(1L, 2L, "VIEW_BOARD"));

        verifyNoInteractions(permissionEvaluationService);
    }

    @Test
    void validateUserHasRole_throwsForbidden_whenUserLacksMembership() {
        when(serverRepository.existsById(2L)).thenReturn(true);
        when(serverMemberRepository.existsByServer_ServerIdAndUser_UserId(2L, 1L)).thenReturn(false);

        assertThrows(AccessDeniedException.class,
                () -> serverAccessValidator.validateUserHasRole(1L, 2L, "VIEW_BOARD"));

        verifyNoInteractions(permissionEvaluationService);
    }

    @Test
    void validateUserHasRole_throwsForbidden_whenPermissionDenied() {
        when(serverRepository.existsById(2L)).thenReturn(true);
        when(serverMemberRepository.existsByServer_ServerIdAndUser_UserId(2L, 1L)).thenReturn(true);
        when(permissionEvaluationService.isAllowed(2L, null, 1L, "EDIT_BOARD_DETAILS")).thenReturn(false);

        assertThrows(AccessDeniedException.class,
                () -> serverAccessValidator.validateUserHasRole(1L, 2L, "EDIT_BOARD_DETAILS"));
    }

    @Test
    void validateUserHasRole_allows_whenPermissionGranted() {
        when(serverRepository.existsById(2L)).thenReturn(true);
        when(serverMemberRepository.existsByServer_ServerIdAndUser_UserId(2L, 1L)).thenReturn(true);
        when(permissionEvaluationService.isAllowed(2L, null, 1L, "VIEW_BOARD")).thenReturn(true);

        assertDoesNotThrow(() -> serverAccessValidator.validateUserHasRole(1L, 2L, "VIEW_BOARD"));

        verify(permissionEvaluationService).isAllowed(2L, null, 1L, "VIEW_BOARD");
    }
}