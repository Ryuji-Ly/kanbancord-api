package com.kanbancord_api.unit.service;

import com.kanbancord_api.permission.DiscordPermissionFlag;
import com.kanbancord_api.permission.KanbanPermission;
import com.kanbancord_api.permission.KanbanPermissionCatalog;
import com.kanbancord_api.permission.KanbanPermissionRepository;
import com.kanbancord_api.permission.Permission;
import com.kanbancord_api.permission.PermissionBootstrapService;
import com.kanbancord_api.permission.PermissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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
