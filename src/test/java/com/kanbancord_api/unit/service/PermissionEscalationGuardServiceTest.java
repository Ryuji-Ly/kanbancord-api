package com.kanbancord_api.unit.service;

import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.model.KanbanPermission;
import com.kanbancord_api.model.Permission;
import com.kanbancord_api.permission.PermissionRank;
import com.kanbancord_api.repository.PermissionRepository;
import com.kanbancord_api.service.PermissionEscalationGuardService;
import com.kanbancord_api.service.PermissionEvaluationService;
import com.kanbancord_api.service.ResourceValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionEscalationGuardServiceTest {

    @Mock
    private PermissionEvaluationService permissionEvaluationService;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private ResourceValidator resourceValidator;

    private PermissionEscalationGuardService service;

    @BeforeEach
    void setUp() {
        service = new PermissionEscalationGuardService(
                permissionEvaluationService,
                permissionRepository,
                resourceValidator);
    }

    @Test
    void validateCreate_deniesActorWithoutManagementRank() {
        when(permissionEvaluationService.calculateEffectiveRank(10L, 99L)).thenReturn(PermissionRank.STANDARD);

        assertThrows(AccessDeniedException.class, () -> service.validateCreate(
                99L,
                10L,
                "USER",
                42L,
                "VIEW_TASK",
                "ALLOW"));
    }

    @Test
    void validateCreate_deniesGrantingHigherRankPermission() {
        when(permissionEvaluationService.calculateEffectiveRank(10L, 99L)).thenReturn(PermissionRank.SERVER_MANAGE);

        assertThrows(AccessDeniedException.class, () -> service.validateCreate(
                99L,
                10L,
                "USER",
                42L,
                "ADMIN",
                "ALLOW"));
    }

    @Test
    void validateCreate_deniesEditingEqualOrHigherRankRoleTarget() {
        when(permissionEvaluationService.calculateEffectiveRank(10L, 99L)).thenReturn(PermissionRank.SERVER_MANAGE);

        Permission rolePermission = permission("MANAGE_SERVER_PERMISSIONS", "ALLOW");
        when(permissionRepository.findBySubjectTypeAndSubjectId("ROLE", 123L)).thenReturn(List.of(rolePermission));
        when(resourceValidator.permissionBelongsToServer(rolePermission, 10L)).thenReturn(true);

        assertThrows(AccessDeniedException.class, () -> service.validateCreate(
                99L,
                10L,
                "ROLE",
                123L,
                "VIEW_TASK",
                "ALLOW"));
    }

    @Test
    void validatePatchState_deniesSelfModificationOfEqualRankCriticalPermission() {
        Permission selfManagePermission = permission("MANAGE_SERVER_PERMISSIONS", "ALLOW");
        selfManagePermission.setId(77L);
        selfManagePermission.setSubjectType("USER");
        selfManagePermission.setSubjectId(99L);

        when(permissionEvaluationService.calculateEffectiveRank(10L, 99L)).thenReturn(PermissionRank.SERVER_MANAGE);

        assertThrows(AccessDeniedException.class, () -> service.validatePatchState(
                99L,
                10L,
                selfManagePermission,
                "DENY"));
    }

    @Test
    void validateCreate_allowsAdminActor() {
        when(permissionEvaluationService.calculateEffectiveRank(10L, 99L)).thenReturn(PermissionRank.ADMIN);

        assertDoesNotThrow(() -> service.validateCreate(
                99L,
                10L,
                "USER",
                42L,
                "ADMIN",
                "ALLOW"));
    }

    private static Permission permission(String key, String state) {
        KanbanPermission kanbanPermission = new KanbanPermission();
        kanbanPermission.setKey(key);

        Permission permission = new Permission();
        permission.setKanbanPermission(kanbanPermission);
        permission.setState(state);
        permission.setScopeType("SERVER");
        permission.setScopeId(10L);
        return permission;
    }
}
