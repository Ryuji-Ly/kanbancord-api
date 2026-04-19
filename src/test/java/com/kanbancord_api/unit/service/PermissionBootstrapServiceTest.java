package com.kanbancord_api.unit.service;

import com.kanbancord_api.model.KanbanPermission;
import com.kanbancord_api.model.Permission;
import com.kanbancord_api.permission.DiscordPermissionFlag;
import com.kanbancord_api.permission.KanbanPermissionCatalog;
import com.kanbancord_api.repository.KanbanPermissionRepository;
import com.kanbancord_api.repository.PermissionRepository;
import com.kanbancord_api.service.PermissionBootstrapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionBootstrapServiceTest {

    @Mock
    private KanbanPermissionRepository kanbanPermissionRepository;
    @Mock
    private PermissionRepository permissionRepository;

    private PermissionBootstrapService service;

    @BeforeEach
    void setUp() {
        service = new PermissionBootstrapService(kanbanPermissionRepository, permissionRepository);
    }

    @Test
    void initializeDefaultServerConfiguration_createsImmutableAdministratorMappings() {
        Map<String, KanbanPermission> catalog = buildCatalog();

        when(kanbanPermissionRepository.findByKey(any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            return Optional.ofNullable(catalog.get(key));
        });
        when(permissionRepository.findByScopeTypeAndScopeIdAndSubjectTypeAndSubjectIdAndKanbanPermission_PermissionId(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        service.initializeDefaultServerConfiguration(42L);

        ArgumentCaptor<Permission> saved = ArgumentCaptor.forClass(Permission.class);
        verify(permissionRepository, atLeastOnce()).save(saved.capture());

        boolean hasImmutableAdminRule = saved.getAllValues().stream()
                .anyMatch(permission -> "DISCORD_PERMISSION".equals(permission.getSubjectType())
                        && permission.getSubjectId().equals(DiscordPermissionFlag.ADMINISTRATOR.getBit())
                        && "ALLOW".equals(permission.getState())
                        && Boolean.TRUE.equals(permission.getIsImmutable()));

        assertTrue(hasImmutableAdminRule);
    }

    @Test
    void initializeBoardConfigurationFromServer_copiesOnlyBoardApplicablePermissions() {
        Map<String, KanbanPermission> catalog = buildCatalog();
        when(kanbanPermissionRepository.findByKey(any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            return Optional.ofNullable(catalog.get(key));
        });

        Permission createBoardServerRule = new Permission();
        createBoardServerRule.setScopeType("SERVER");
        createBoardServerRule.setScopeId(1L);
        createBoardServerRule.setSubjectType("DISCORD_PERMISSION");
        createBoardServerRule.setSubjectId(DiscordPermissionFlag.MANAGE_GUILD.getBit());
        createBoardServerRule.setKanbanPermission(catalog.get("CREATE_BOARD"));
        createBoardServerRule.setState("ALLOW");
        createBoardServerRule.setPriority(100);

        Permission editTaskServerRule = new Permission();
        editTaskServerRule.setScopeType("SERVER");
        editTaskServerRule.setScopeId(1L);
        editTaskServerRule.setSubjectType("DISCORD_PERMISSION");
        editTaskServerRule.setSubjectId(DiscordPermissionFlag.MANAGE_MESSAGES.getBit());
        editTaskServerRule.setKanbanPermission(catalog.get("EDIT_TASK"));
        editTaskServerRule.setState("ALLOW");
        editTaskServerRule.setPriority(100);

        when(permissionRepository.findByScopeTypeAndScopeIdOrderByPriorityDescIdDesc("SERVER", 1L))
                .thenReturn(List.of(createBoardServerRule, editTaskServerRule));
        when(permissionRepository.findByScopeTypeAndScopeIdAndSubjectTypeAndSubjectIdAndKanbanPermission_PermissionId(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        service.initializeBoardConfigurationFromServer(1L, 99L);

        ArgumentCaptor<Permission> saved = ArgumentCaptor.forClass(Permission.class);
        verify(permissionRepository, atLeastOnce()).save(saved.capture());

        boolean hasBoardEditTask = saved.getAllValues().stream()
                .anyMatch(permission -> "BOARD".equals(permission.getScopeType())
                        && Long.valueOf(99L).equals(permission.getScopeId())
                        && permission.getKanbanPermission() != null
                        && "EDIT_TASK".equals(permission.getKanbanPermission().getKey()));

        boolean hasBoardCreateBoard = saved.getAllValues().stream()
                .anyMatch(permission -> "BOARD".equals(permission.getScopeType())
                        && permission.getKanbanPermission() != null
                        && "CREATE_BOARD".equals(permission.getKanbanPermission().getKey()));

        assertTrue(hasBoardEditTask);
        assertFalse(hasBoardCreateBoard);
    }

    private static Map<String, KanbanPermission> buildCatalog() {
        Map<String, KanbanPermission> catalog = new HashMap<>();
        int index = 1;
        for (KanbanPermissionCatalog item : KanbanPermissionCatalog.values()) {
            KanbanPermission permission = new KanbanPermission();
            permission.setPermissionId(index++);
            permission.setKey(item.getKey());
            permission.setName(item.getName());
            permission.setCategory(item.getCategory());
            permission.setIsSystem(true);
            catalog.put(item.getKey(), permission);
        }
        return catalog;
    }
}
