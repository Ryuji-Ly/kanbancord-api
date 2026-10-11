package com.kanbancord_api.permission;

import com.kanbancord_api.exception.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * One-step permission setups, for the whole server or one board. A preset only writes rules for @everyone
 * and the chosen staff role, through {@link PermissionRuleCommands}, so each change is checked and logged like
 * any other, and the whole preset applies or nothing does. Other rules are left as they are.
 *
 * <p>The presets rely on @everyone being its own layer beneath the other roles ({@link PermissionResolver}):
 * deny something to @everyone, allow it to the staff role, and staff have it.
 */
@Service
@Transactional
public class PermissionPresetService {

    public enum Preset {
        /** Back to the defaults: what the Discord permissions allow, @everyone's preset rules removed. */
        EVERYONE,
        /** Everyone can see the boards; only the staff role can create, move and change tasks. */
        MEMBERS_VIEW,
        /** Only the staff role can see the boards, and everything in them. */
        STAFF_ONLY
    }

    public record PresetRequest(Preset preset, String scopeType, Long scopeId, Long staffRoleId) {
    }

    public record PresetResult(int created, int changed, int removed) {
    }

    /** Seeing a board and its tasks. */
    static final Set<String> VIEW_KEYS = Set.of("VIEW_BOARD", "VIEW_TASK");
    /** Working with tasks: everything a moderator may do with them by default. */
    static final Set<String> TASK_KEYS = Set.of(
            "CREATE_TASK", "MOVE_TASK", "EDIT_TASK", "DELETE_TASK", "ARCHIVE_TASK",
            "CREATE_TASK_COMMENT", "DELETE_TASK_COMMENT", "APPLY_LABEL_TO_TASK", "REMOVE_LABEL_FROM_TASK",
            "ASSIGN_TASK_SELF", "ASSIGN_TASK_OTHERS");
    private static final int PRIORITY = 100;

    private final PermissionRuleCommands commands;
    private final PermissionRepository permissionRepository;
    private final KanbanPermissionRepository kanbanPermissionRepository;

    public PermissionPresetService(PermissionRuleCommands commands, PermissionRepository permissionRepository,
                                   KanbanPermissionRepository kanbanPermissionRepository) {
        this.commands = commands;
        this.permissionRepository = permissionRepository;
        this.kanbanPermissionRepository = kanbanPermissionRepository;
    }

    public PresetResult apply(Long serverId, Long actorUserId, PresetRequest request) {
        if (request.preset() == null) {
            throw new BadRequestException("Choose a preset");
        }
        String scopeType = request.scopeType() == null ? "SERVER" : request.scopeType();
        if (!scopeType.equals("SERVER") && !scopeType.equals("BOARD")) {
            throw new BadRequestException("Scope type must be SERVER or BOARD");
        }
        Long scopeId = scopeType.equals("SERVER") ? serverId : request.scopeId();
        if (scopeId == null) {
            throw new BadRequestException("Choose the board");
        }
        // @everyone's role has the server's id.
        Long everyone = serverId;
        Long staff = request.staffRoleId();
        if (request.preset() != Preset.EVERYONE) {
            if (staff == null) {
                throw new BadRequestException("Choose the role that counts as staff");
            }
            if (staff.equals(everyone)) {
                throw new BadRequestException("The staff role cannot be @everyone");
            }
        }

        Counter counter = new Counter();
        Set<String> touched = new LinkedHashSet<>();
        touched.addAll(VIEW_KEYS);
        touched.addAll(TASK_KEYS);
        switch (request.preset()) {
            case EVERYONE -> {
                for (String key : touched) {
                    remove(serverId, actorUserId, scopeType, scopeId, everyone, key, counter);
                }
            }
            case MEMBERS_VIEW -> {
                for (String key : VIEW_KEYS) {
                    remove(serverId, actorUserId, scopeType, scopeId, everyone, key, counter);
                }
                for (String key : TASK_KEYS) {
                    set(serverId, actorUserId, scopeType, scopeId, everyone, key, "DENY", counter);
                }
                for (String key : touched) {
                    set(serverId, actorUserId, scopeType, scopeId, staff, key, "ALLOW", counter);
                }
            }
            case STAFF_ONLY -> {
                for (String key : TASK_KEYS) {
                    remove(serverId, actorUserId, scopeType, scopeId, everyone, key, counter);
                }
                for (String key : VIEW_KEYS) {
                    set(serverId, actorUserId, scopeType, scopeId, everyone, key, "DENY", counter);
                }
                for (String key : touched) {
                    set(serverId, actorUserId, scopeType, scopeId, staff, key, "ALLOW", counter);
                }
            }
        }
        return new PresetResult(counter.created, counter.changed, counter.removed);
    }

    private void set(Long serverId, Long actorUserId, String scopeType, Long scopeId, Long roleId, String key,
                     String state, Counter counter) {
        KanbanPermission kanbanPermission = kanbanPermission(key);
        Optional<Permission> existing = existing(scopeType, scopeId, roleId, kanbanPermission);
        if (existing.isEmpty()) {
            commands.create(serverId, actorUserId, new PermissionRequest(scopeType, scopeId, "ROLE", roleId,
                    kanbanPermission.getPermissionId(), state, PRIORITY, false));
            counter.created++;
        } else if (!state.equals(existing.get().getState())) {
            commands.setState(serverId, existing.get().getId(), actorUserId, state);
            counter.changed++;
        }
    }

    private void remove(Long serverId, Long actorUserId, String scopeType, Long scopeId, Long roleId, String key,
                        Counter counter) {
        Optional<Permission> existing = existing(scopeType, scopeId, roleId, kanbanPermission(key));
        if (existing.isPresent()) {
            commands.delete(serverId, existing.get().getId(), actorUserId);
            counter.removed++;
        }
    }

    private Optional<Permission> existing(String scopeType, Long scopeId, Long roleId, KanbanPermission kanbanPermission) {
        return permissionRepository.findByScopeTypeAndScopeIdAndSubjectTypeAndSubjectIdAndKanbanPermission_PermissionId(
                scopeType, scopeId, "ROLE", roleId, kanbanPermission.getPermissionId());
    }

    private KanbanPermission kanbanPermission(String key) {
        return kanbanPermissionRepository.findByKey(key)
                .orElseThrow(() -> new IllegalStateException("Unknown kanban permission key: " + key));
    }

    private static final class Counter {
        int created;
        int changed;
        int removed;
    }
}
