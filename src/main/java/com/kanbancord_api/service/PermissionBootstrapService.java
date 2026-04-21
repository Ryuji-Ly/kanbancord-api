package com.kanbancord_api.service;

import com.kanbancord_api.model.KanbanPermission;
import com.kanbancord_api.model.Permission;
import com.kanbancord_api.permission.DiscordPermissionFlag;
import com.kanbancord_api.permission.KanbanPermissionCatalog;
import com.kanbancord_api.repository.KanbanPermissionRepository;
import com.kanbancord_api.repository.PermissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
public class PermissionBootstrapService {

    private static final String SCOPE_SERVER = "SERVER";
    private static final String SCOPE_BOARD = "BOARD";
    private static final String SUBJECT_DISCORD_PERMISSION = "DISCORD_PERMISSION";
    private static final String STATE_ALLOW = "ALLOW";

    private final KanbanPermissionRepository kanbanPermissionRepository;
    private final PermissionRepository permissionRepository;

    public PermissionBootstrapService(
            KanbanPermissionRepository kanbanPermissionRepository,
            PermissionRepository permissionRepository) {
        this.kanbanPermissionRepository = kanbanPermissionRepository;
        this.permissionRepository = permissionRepository;
    }

    public void ensureCatalogSeeded() {
        for (KanbanPermissionCatalog item : KanbanPermissionCatalog.values()) {
            KanbanPermission existing = kanbanPermissionRepository.findByKey(item.getKey())
                    .orElseGet(KanbanPermission::new);

            existing.setKey(item.getKey());
            existing.setName(item.getName());
            existing.setDescription(item.getDescription());
            existing.setCategory(item.getCategory());
            existing.setIsSystem(true);

            kanbanPermissionRepository.save(existing);
        }
    }

    public void initializeDefaultServerConfiguration(Long serverId) {
        ensureCatalogSeeded();

        // ADMINISTRATOR always receives immutable ALLOW for ADMIN — which implies all
        // permissions.
        upsertPermission(
                SCOPE_SERVER,
                serverId,
                SUBJECT_DISCORD_PERMISSION,
                DiscordPermissionFlag.ADMINISTRATOR.getBit(),
                "ADMIN",
                STATE_ALLOW,
                10_000,
                true);

        // Baseline Discord permission mappings into Kanban capabilities.
        mapDiscordFlagAtServer(serverId, DiscordPermissionFlag.MANAGE_GUILD, "EDIT_SERVER_DETAILS", 220, false);
        mapDiscordFlagAtServer(serverId, DiscordPermissionFlag.MANAGE_GUILD, "EDIT_SERVER_PERMISSIONS", 220, false);
        mapDiscordFlagAtServer(serverId, DiscordPermissionFlag.MANAGE_GUILD, "CREATE_BOARD", 200, false);
        mapDiscordFlagAtServer(serverId, DiscordPermissionFlag.MANAGE_GUILD, "EDIT_BOARD_DETAILS", 190, false);
        mapDiscordFlagAtServer(serverId, DiscordPermissionFlag.MANAGE_GUILD, "EDIT_BOARD_PERMISSIONS", 190, false);

        mapDiscordFlagAtServer(serverId, DiscordPermissionFlag.MANAGE_CHANNELS, "CREATE_COLUMN", 180, false);
        mapDiscordFlagAtServer(serverId, DiscordPermissionFlag.MANAGE_CHANNELS, "EDIT_COLUMN", 180, false);
        mapDiscordFlagAtServer(serverId, DiscordPermissionFlag.MANAGE_CHANNELS, "DELETE_COLUMN", 180, false);
        mapDiscordFlagAtServer(serverId, DiscordPermissionFlag.MANAGE_CHANNELS, "MOVE_COLUMN", 180, false);

        mapDiscordFlagAtServer(serverId, DiscordPermissionFlag.VIEW_AUDIT_LOG, "VIEW_AUDIT_LOG", 180, false);
        mapDiscordFlagAtServer(serverId, DiscordPermissionFlag.MANAGE_ROLES, "MANAGE_SERVER_ROLES", 170, false);
        mapDiscordFlagAtServer(serverId, DiscordPermissionFlag.MODERATE_MEMBERS, "MANAGE_SERVER_MEMBERS", 170, false);

        mapDiscordFlagAtServer(serverId, DiscordPermissionFlag.SEND_MESSAGES, "CREATE_TASK", 120, false);
        mapDiscordFlagAtServer(serverId, DiscordPermissionFlag.SEND_MESSAGES, "CREATE_TASK_COMMENT", 120, false);
        mapDiscordFlagAtServer(serverId, DiscordPermissionFlag.MANAGE_MESSAGES, "EDIT_TASK", 130, false);
        mapDiscordFlagAtServer(serverId, DiscordPermissionFlag.MANAGE_MESSAGES, "EDIT_TASK_COMMENT", 130, false);
        mapDiscordFlagAtServer(serverId, DiscordPermissionFlag.MANAGE_MESSAGES, "DELETE_TASK_COMMENT", 130, false);
    }

    public void initializeBoardConfigurationFromServer(Long serverId, Long boardId) {
        ensureCatalogSeeded();

        List<Permission> serverPermissions = permissionRepository
                .findByScopeTypeAndScopeIdOrderByPriorityDescIdDesc(SCOPE_SERVER, serverId);

        for (Permission source : serverPermissions) {
            if (source.getKanbanPermission() == null) {
                continue;
            }

            boolean boardAllowed = KanbanPermissionCatalog.fromKey(source.getKanbanPermission().getKey())
                    .map(KanbanPermissionCatalog::isBoardScopeAllowed)
                    .orElse(false);

            if (!boardAllowed) {
                continue;
            }

            upsertPermission(
                    SCOPE_BOARD,
                    boardId,
                    source.getSubjectType(),
                    source.getSubjectId(),
                    source.getKanbanPermission().getKey(),
                    source.getState(),
                    source.getPriority(),
                    source.getIsImmutable() != null && source.getIsImmutable());
        }
    }

    private void mapDiscordFlagAtServer(
            Long serverId,
            DiscordPermissionFlag discordFlag,
            String kanbanPermissionKey,
            int priority,
            boolean immutable) {
        upsertPermission(
                SCOPE_SERVER,
                serverId,
                SUBJECT_DISCORD_PERMISSION,
                discordFlag.getBit(),
                kanbanPermissionKey,
                STATE_ALLOW,
                priority,
                immutable);
    }

    private void upsertPermission(
            String scopeType,
            Long scopeId,
            String subjectType,
            Long subjectId,
            String kanbanPermissionKey,
            String state,
            Integer priority,
            boolean immutable) {

        KanbanPermission kanbanPermission = kanbanPermissionRepository.findByKey(kanbanPermissionKey)
                .orElseThrow(() -> new IllegalStateException("Unknown kanban permission key: " + kanbanPermissionKey));

        Permission permission = permissionRepository
                .findByScopeTypeAndScopeIdAndSubjectTypeAndSubjectIdAndKanbanPermission_PermissionId(
                        scopeType,
                        scopeId,
                        subjectType,
                        subjectId,
                        kanbanPermission.getPermissionId())
                .orElseGet(Permission::new);

        permission.setScopeType(scopeType);
        permission.setScopeId(scopeId);
        permission.setSubjectType(subjectType);
        permission.setSubjectId(subjectId);
        permission.setKanbanPermission(kanbanPermission);
        permission.setState(state);
        permission.setPriority(priority);

        boolean existingImmutable = permission.getIsImmutable() != null && permission.getIsImmutable();
        permission.setIsImmutable(existingImmutable || immutable);

        permissionRepository.save(permission);
    }

    public List<String> validatePermissionTablesReady() {
        ensureCatalogSeeded();
        List<String> issues = new ArrayList<>();

        for (KanbanPermissionCatalog item : KanbanPermissionCatalog.values()) {
            KanbanPermission permission = kanbanPermissionRepository.findByKey(item.getKey()).orElse(null);
            if (permission == null) {
                issues.add("Missing catalog permission: " + item.getKey());
                continue;
            }
            if (permission.getIsSystem() == null || !permission.getIsSystem()) {
                issues.add("Catalog permission is not marked system: " + item.getKey());
            }
        }

        return issues;
    }
}
