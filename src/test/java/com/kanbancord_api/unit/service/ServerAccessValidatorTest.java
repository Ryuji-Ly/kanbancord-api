package com.kanbancord_api.unit.service;

import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.repository.BoardRepository;
import com.kanbancord_api.repository.ServerMemberRepository;
import com.kanbancord_api.repository.ServerRepository;
import com.kanbancord_api.service.PermissionEvaluationService;
import com.kanbancord_api.service.ServerAccessValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private BoardRepository boardRepository;

    @Mock
    private PermissionEvaluationService permissionEvaluationService;

    private static final PermissionEvaluationService.Decision ALLOWED =
            new PermissionEvaluationService.Decision(true, "ROLE", "SERVER", 2L, 1L);

    private ServerAccessValidator serverAccessValidator;

    @BeforeEach
    void setUp() {
        serverAccessValidator = new ServerAccessValidator(
                serverMemberRepository,
                serverRepository,
                boardRepository,
                permissionEvaluationService);
    }

    @Test
    void validateUserHasPermission_allowsWhenPermissionBlank_afterMembershipCheck() {
        when(serverRepository.existsById(2L)).thenReturn(true);
        when(serverMemberRepository.existsByServer_ServerIdAndUser_UserId(2L, 1L)).thenReturn(true);

        assertDoesNotThrow(() -> serverAccessValidator.validateUserHasPermission(1L, 2L, null, " "));

        verifyNoInteractions(permissionEvaluationService);
    }

    @Test
    void validateUserHasPermission_throwsNotFound_whenServerMissing() {
        when(serverRepository.existsById(2L)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class,
                () -> serverAccessValidator.validateUserHasPermission(1L, 2L, null, "VIEW_BOARD"));

        verifyNoInteractions(permissionEvaluationService);
    }

    @Test
    void validateUserHasPermission_throwsForbidden_whenUserLacksMembership() {
        when(serverRepository.existsById(2L)).thenReturn(true);
        when(serverMemberRepository.existsByServer_ServerIdAndUser_UserId(2L, 1L)).thenReturn(false);

        assertThrows(AccessDeniedException.class,
                () -> serverAccessValidator.validateUserHasPermission(1L, 2L, null, "VIEW_BOARD"));

        verifyNoInteractions(permissionEvaluationService);
    }

    @Test
    void validateUserHasPermission_throwsForbidden_whenPermissionDenied() {
        when(serverRepository.existsById(2L)).thenReturn(true);
        when(serverMemberRepository.existsByServer_ServerIdAndUser_UserId(2L, 1L)).thenReturn(true);
        when(permissionEvaluationService.isAllowed(2L, null, 1L, "EDIT_BOARD_DETAILS")).thenReturn(false);

        assertThrows(AccessDeniedException.class,
                () -> serverAccessValidator.validateUserHasPermission(1L, 2L, null, "EDIT_BOARD_DETAILS"));
    }

    @Test
    void validateUserHasPermission_allows_whenPermissionGranted() {
        when(serverRepository.existsById(2L)).thenReturn(true);
        when(serverMemberRepository.existsByServer_ServerIdAndUser_UserId(2L, 1L)).thenReturn(true);
        when(permissionEvaluationService.isAllowed(2L, null, 1L, "VIEW_BOARD")).thenReturn(true);

        assertDoesNotThrow(() -> serverAccessValidator.validateUserHasPermission(1L, 2L, null, "VIEW_BOARD"));

        verify(permissionEvaluationService).isAllowed(2L, null, 1L, "VIEW_BOARD");
    }

    @Test
    void validateUserHasPermission_evaluatesAtBoardScope_forBoardOfThisServer() {
        when(serverRepository.existsById(2L)).thenReturn(true);
        when(serverMemberRepository.existsByServer_ServerIdAndUser_UserId(2L, 1L)).thenReturn(true);
        when(boardRepository.existsByBoardIdAndServer_ServerId(5L, 2L)).thenReturn(true);
        when(permissionEvaluationService.resolveAll(2L, 5L, 1L, List.of("VIEW_BOARD", "EDIT_TASK")))
                .thenReturn(Map.of("VIEW_BOARD", ALLOWED, "EDIT_TASK", ALLOWED));

        assertDoesNotThrow(() -> serverAccessValidator.validateUserHasPermission(1L, 2L, 5L, "EDIT_TASK"));
    }

    @Test
    void validateUserHasPermission_insideBoard_alsoRequiresViewBoard() {
        when(serverRepository.existsById(2L)).thenReturn(true);
        when(serverMemberRepository.existsByServer_ServerIdAndUser_UserId(2L, 1L)).thenReturn(true);
        when(boardRepository.existsByBoardIdAndServer_ServerId(5L, 2L)).thenReturn(true);
        when(permissionEvaluationService.resolveAll(2L, 5L, 1L, List.of("VIEW_BOARD", "VIEW_TASK")))
                .thenReturn(Map.of("VIEW_BOARD", PermissionEvaluationService.Decision.NONE, "VIEW_TASK", ALLOWED));

        AccessDeniedException denied = assertThrows(AccessDeniedException.class,
                () -> serverAccessValidator.validateUserHasPermission(1L, 2L, 5L, "VIEW_TASK"));
        assertEquals("Missing required permission: VIEW_BOARD", denied.getMessage());
    }

    @Test
    void validateUserHasPermission_rejectsBoardFromAnotherServer_beforeEvaluating() {
        when(serverRepository.existsById(2L)).thenReturn(true);
        when(serverMemberRepository.existsByServer_ServerIdAndUser_UserId(2L, 1L)).thenReturn(true);
        when(boardRepository.existsByBoardIdAndServer_ServerId(5L, 2L)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class,
                () -> serverAccessValidator.validateUserHasPermission(1L, 2L, 5L, "EDIT_TASK"));

        verifyNoInteractions(permissionEvaluationService);
    }
}