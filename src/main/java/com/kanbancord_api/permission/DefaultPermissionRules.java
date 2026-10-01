package com.kanbancord_api.permission;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What each Discord permission allows out of the box, each step adding to the one before. A new server
 * gets these as its stored rules, to edit once custom permissions are on; while custom permissions are
 * off, these apply as they are here, whatever the stored rules say.
 */
public final class DefaultPermissionRules {

    /** One Discord permission and the keys it allows. */
    public record Grant(DiscordPermissionFlag flag, int priority, boolean immutable, Set<String> keys) {
    }

    private static final Set<String> VIEW = keys(Set.of(), "VIEW_SERVER", "VIEW_BOARD", "VIEW_TASK");
    private static final Set<String> CONTRIBUTE = keys(VIEW,
            "CREATE_TASK", "MOVE_TASK", "CREATE_TASK_COMMENT", "APPLY_LABEL_TO_TASK", "REMOVE_LABEL_FROM_TASK",
            "ASSIGN_TASK_SELF");
    private static final Set<String> MODERATE = keys(CONTRIBUTE,
            "EDIT_TASK", "DELETE_TASK", "ARCHIVE_TASK", "DELETE_TASK_COMMENT", "ASSIGN_TASK_OTHERS");
    private static final Set<String> MANAGE_BOARDS = keys(MODERATE,
            "CREATE_COLUMN", "EDIT_COLUMN", "DELETE_COLUMN", "MOVE_COLUMN",
            "EDIT_BOARD_DETAILS", "ARCHIVE_BOARD", "EDIT_BOARD_PERMISSIONS",
            "CREATE_LABEL", "EDIT_LABEL", "DELETE_LABEL", "MANAGE_PRIORITIES", "VIEW_AUDIT_LOG");
    private static final Set<String> MANAGE_SERVER = keys(MANAGE_BOARDS,
            "MANAGE_SERVER_PERMISSIONS", "CREATE_BOARD", "DELETE_BOARD");

    private static final List<Grant> GRANTS = List.of(
            // Administrators may do everything, and that cannot be changed.
            new Grant(DiscordPermissionFlag.ADMINISTRATOR, 10_000, true, Set.of(PermissionResolver.ADMIN_KEY)),
            new Grant(DiscordPermissionFlag.VIEW_CHANNEL, 100, false, VIEW),
            new Grant(DiscordPermissionFlag.SEND_MESSAGES, 120, false, CONTRIBUTE),
            new Grant(DiscordPermissionFlag.MANAGE_MESSAGES, 130, false, MODERATE),
            new Grant(DiscordPermissionFlag.MANAGE_CHANNELS, 180, false, MANAGE_BOARDS),
            new Grant(DiscordPermissionFlag.VIEW_AUDIT_LOG, 180, false, keys(VIEW, "VIEW_AUDIT_LOG")),
            new Grant(DiscordPermissionFlag.MANAGE_GUILD, 200, false, MANAGE_SERVER));

    private DefaultPermissionRules() {
    }

    public static List<Grant> grants() {
        return GRANTS;
    }

    /** The defaults as rules of the server, not stored (they have no id). */
    public static List<Permission> forServer(Long serverId) {
        List<Permission> rules = new ArrayList<>();
        for (Grant grant : GRANTS) {
            for (String key : grant.keys()) {
                KanbanPermission kanbanPermission = new KanbanPermission();
                kanbanPermission.setKey(key);
                KanbanPermissionCatalog.fromKey(key).ifPresent(item -> kanbanPermission.setName(item.getName()));

                Permission rule = new Permission();
                rule.setScopeType("SERVER");
                rule.setScopeId(serverId);
                rule.setSubjectType("DISCORD_PERMISSION");
                rule.setSubjectId(grant.flag().getBit());
                rule.setKanbanPermission(kanbanPermission);
                rule.setState("ALLOW");
                rule.setPriority(grant.priority());
                rule.setIsImmutable(grant.immutable());
                rules.add(rule);
            }
        }
        return rules;
    }

    private static Set<String> keys(Set<String> base, String... more) {
        Set<String> keys = new LinkedHashSet<>(base);
        keys.addAll(List.of(more));
        return Set.copyOf(keys);
    }
}
