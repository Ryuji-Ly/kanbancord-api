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

        // ADMINISTRATOR → immutable ADMIN (implies everything)
        upsertPermission(SCOPE_SERVER, serverId, SUBJECT_DISCORD_PERMISSION,
                DiscordPermissionFlag.ADMINISTRATOR.getBit(),
                "ADMIN", STATE_ALLOW, 10_000, true);

        // ── TIER 1 ── VIEW_CHANNEL (100) — basic read access ─────────────────────
        mapPermissions(serverId, DiscordPermissionFlag.VIEW_CHANNEL, 100,
                "VIEW_SERVER", "VIEW_BOARD", "VIEW_TASK");

        // ── TIER 2 ── SEND_MESSAGES (120) — regular contributors ─────────────────
        // Also includes all TIER 1 permissions cascaded at priority 120
        mapPermissions(serverId, DiscordPermissionFlag.SEND_MESSAGES, 120,
                // cascade from TIER 1
                "VIEW_SERVER", "VIEW_BOARD", "VIEW_TASK",
                // TIER 2 own
                "CREATE_TASK", "MOVE_TASK", "CREATE_TASK_COMMENT",
                "APPLY_LABEL_TO_TASK", "REMOVE_LABEL_FROM_TASK",
                "ASSIGN_TASK_SELF");

        // ── TIER 3 ── MANAGE_MESSAGES (130) — moderators ─────────────────────────
        // Cascades TIER 1 + TIER 2
        mapPermissions(serverId, DiscordPermissionFlag.MANAGE_MESSAGES, 130,
                // cascade
                "VIEW_SERVER", "VIEW_BOARD", "VIEW_TASK",
                "CREATE_TASK", "MOVE_TASK", "CREATE_TASK_COMMENT",
                "APPLY_LABEL_TO_TASK", "REMOVE_LABEL_FROM_TASK",
                "ASSIGN_TASK_SELF",
                // TIER 3 own
                "EDIT_TASK", "DELETE_TASK", "ARCHIVE_TASK",
                "EDIT_TASK_COMMENT", "DELETE_TASK_COMMENT",
                "ASSIGN_TASK_OTHERS");

        // ── TIER 4 ── MANAGE_CHANNELS (180) — channel / board managers ───────────
        // Cascades TIER 1 + 2 + 3
        mapPermissions(serverId, DiscordPermissionFlag.MANAGE_CHANNELS, 180,
                // cascade
                "VIEW_SERVER", "VIEW_BOARD", "VIEW_TASK",
                "CREATE_TASK", "MOVE_TASK", "CREATE_TASK_COMMENT",
                "APPLY_LABEL_TO_TASK", "REMOVE_LABEL_FROM_TASK",
                "ASSIGN_TASK_SELF",
                "EDIT_TASK", "DELETE_TASK", "ARCHIVE_TASK",
                "EDIT_TASK_COMMENT", "DELETE_TASK_COMMENT",
                "ASSIGN_TASK_OTHERS",
                // TIER 4 own
                "CREATE_COLUMN", "EDIT_COLUMN", "DELETE_COLUMN", "MOVE_COLUMN",
                "EDIT_BOARD_DETAILS", "ARCHIVE_BOARD", "EDIT_BOARD_PERMISSIONS",
                "CREATE_LABEL", "EDIT_LABEL", "DELETE_LABEL",
                "VIEW_AUDIT_LOG");

        // ── TIER 5 ── VIEW_AUDIT_LOG (180) — audit reviewers ─────────────────────
        mapPermissions(serverId, DiscordPermissionFlag.VIEW_AUDIT_LOG, 180,
                // cascade TIER 1
                "VIEW_SERVER", "VIEW_BOARD", "VIEW_TASK",
                // own
                "VIEW_AUDIT_LOG");

        // ── TIER 6 ── MANAGE_GUILD (200+) — server admins ────────────────────────
        // Cascades all tiers
        mapPermissions(serverId, DiscordPermissionFlag.MANAGE_GUILD, 200,
                // cascade all lower tiers
                "VIEW_SERVER", "VIEW_BOARD", "VIEW_TASK",
                "CREATE_TASK", "MOVE_TASK", "CREATE_TASK_COMMENT",
                "APPLY_LABEL_TO_TASK", "REMOVE_LABEL_FROM_TASK",
                "ASSIGN_TASK_SELF",
                "EDIT_TASK", "DELETE_TASK", "ARCHIVE_TASK",
                "EDIT_TASK_COMMENT", "DELETE_TASK_COMMENT",
                "ASSIGN_TASK_OTHERS",
                "CREATE_COLUMN", "EDIT_COLUMN", "DELETE_COLUMN", "MOVE_COLUMN",
                "EDIT_BOARD_DETAILS", "ARCHIVE_BOARD", "EDIT_BOARD_PERMISSIONS",
                "CREATE_LABEL", "EDIT_LABEL", "DELETE_LABEL",
                "VIEW_AUDIT_LOG",
                // TIER 6 own
                "MANAGE_SERVER_PERMISSIONS", "CREATE_BOARD", "DELETE_BOARD");
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

    private void mapPermissions(
            Long serverId,
            DiscordPermissionFlag discordFlag,
            int priority,
            String... kanbanPermissionKeys) {
        for (String key : kanbanPermissionKeys) {
            upsertPermission(
                    SCOPE_SERVER,
                    serverId,
                    SUBJECT_DISCORD_PERMISSION,
                    discordFlag.getBit(),
                    key,
                    STATE_ALLOW,
                    priority,
                    false);
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
