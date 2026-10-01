package com.kanbancord_api.permission;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Pure permission resolution over a {@link PermissionSnapshot}. No I/O: all rules are already loaded.
 *
 * <p>Rules are applied as layers, in this order, each later layer overriding the earlier ones:
 * <ol>
 * <li>server scope: DISCORD_PERMISSION rules, then ROLE rules, then USER rules</li>
 * <li>board scope (the board's overrides): DISCORD_PERMISSION, then ROLE, then USER</li>
 * </ol>
 * A layer only takes effect if it has at least one rule matching the user and key. Within a layer a
 * DENY beats any ALLOW. If no layer matches, the key is denied.
 *
 * <p>A user who resolves to ADMIN (at server scope) is allowed every key.
 *
 * <p>With open permissions on, every member who can view channels and send messages is allowed the
 * {@link #OPEN_KEYS}, whatever the rules say: working with boards, columns, labels and tasks. Managing
 * the server, board permissions, deleting or archiving boards, the audit log and moderating other
 * people's comments still go by the rules.
 */
public final class PermissionResolver {

    public static final String ADMIN_KEY = "ADMIN";

    /** What everyone who can talk in the server may do with open permissions on. */
    public static final Set<String> OPEN_KEYS = Set.of(
            "VIEW_SERVER", "VIEW_BOARD", "VIEW_TASK", "CREATE_BOARD", "EDIT_BOARD_DETAILS",
            "CREATE_COLUMN", "EDIT_COLUMN", "DELETE_COLUMN", "MOVE_COLUMN",
            "CREATE_TASK", "EDIT_TASK", "MOVE_TASK", "DELETE_TASK", "ARCHIVE_TASK",
            "ASSIGN_TASK_SELF", "ASSIGN_TASK_OTHERS", "CREATE_TASK_COMMENT",
            "CREATE_LABEL", "EDIT_LABEL", "DELETE_LABEL", "APPLY_LABEL_TO_TASK", "REMOVE_LABEL_FROM_TASK",
            "MANAGE_PRIORITIES");
    static final String OPEN_TIER = "OPEN";
    private static final Set<Long> CAN_TALK = Set.of(DiscordPermissionFlag.VIEW_CHANNEL.getBit(),
            DiscordPermissionFlag.SEND_MESSAGES.getBit());

    static final String SUBJECT_USER = "USER";
    static final String SUBJECT_ROLE = "ROLE";
    static final String SUBJECT_DISCORD_PERMISSION = "DISCORD_PERMISSION";
    private static final String STATE_ALLOW = "ALLOW";
    private static final List<String> TIER_ORDER = List.of(SUBJECT_DISCORD_PERMISSION, SUBJECT_ROLE, SUBJECT_USER);

    /** Deterministic choice of the rule reported as the decision's source within a layer. */
    private static final Comparator<Permission> REPORTING_ORDER = Comparator
            .comparing(Permission::getId, Comparator.nullsFirst(Comparator.reverseOrder()));

    private PermissionResolver() {
    }

    public static PermissionEvaluationService.Decision resolve(PermissionSnapshot snapshot, String permissionKey) {
        if (openlyAllowed(snapshot, permissionKey)) {
            return new PermissionEvaluationService.Decision(true, OPEN_TIER, "SERVER", null, null);
        }
        if (!ADMIN_KEY.equals(permissionKey)) {
            PermissionEvaluationService.Decision admin = resolveKey(snapshot, ADMIN_KEY);
            if (admin.allowed()) {
                return new PermissionEvaluationService.Decision(true, ADMIN_KEY, admin.sourceScopeType(),
                        admin.sourceScopeId(), admin.sourcePermissionId());
            }
        }
        return resolveKey(snapshot, permissionKey);
    }

    /**
     * The highest catalog rank among the keys the snapshot allows (READONLY if none).
     */
    public static PermissionRank effectiveRank(PermissionSnapshot snapshot) {
        PermissionRank best = PermissionRank.READONLY;
        for (KanbanPermissionCatalog item : KanbanPermissionCatalog.values()) {
            if (item.getRank().getWeight() > best.getWeight() && resolve(snapshot, item.getKey()).allowed()) {
                best = item.getRank();
            }
        }
        return best;
    }

    /**
     * One layer that has rules for the user and key: every matching rule, and the one that decided the
     * layer (a DENY if there is one).
     */
    public record Layer(String scopeType, String tier, List<Permission> rules, Permission decider) {
    }

    /**
     * Every layer with rules for the user and key, in the order they apply; the last one decides. Says
     * why {@link #resolve} came out as it did, rule by rule. Leaves out administrators and open
     * permissions, which come first.
     */
    public static List<Layer> trace(PermissionSnapshot snapshot, String key) {
        List<Layer> layers = new ArrayList<>();
        for (String scopeType : List.of("SERVER", "BOARD")) {
            List<Permission> scopeRules = "SERVER".equals(scopeType) ? snapshot.serverRules() : snapshot.boardRules();
            for (String tier : TIER_ORDER) {
                List<Permission> matching = matching(scopeRules, key, tier, subjectIds(snapshot, tier));
                if (!matching.isEmpty()) {
                    layers.add(new Layer(scopeType, tier, matching, decider(matching)));
                }
            }
        }
        return layers;
    }

    /** Whether the snapshot's open permissions allow the key, whatever the rules say. */
    public static boolean openlyAllowed(PermissionSnapshot snapshot, String key) {
        return snapshot.openPermissions() && OPEN_KEYS.contains(key) && snapshot.member()
                && snapshot.discordFlagBits().containsAll(CAN_TALK);
    }

    private static PermissionEvaluationService.Decision resolveKey(PermissionSnapshot snapshot, String key) {
        PermissionEvaluationService.Decision decision = PermissionEvaluationService.Decision.NONE;
        for (List<Permission> scopeRules : List.of(snapshot.serverRules(), snapshot.boardRules())) {
            for (String tier : TIER_ORDER) {
                Optional<Permission> layerResult = resolveLayer(scopeRules, key, tier, subjectIds(snapshot, tier));
                if (layerResult.isPresent()) {
                    decision = toDecision(layerResult.get(), tier);
                }
            }
        }
        return decision;
    }

    /** The deciding rule of one layer: a DENY if the layer has any, otherwise an ALLOW. */
    private static Optional<Permission> resolveLayer(
            List<Permission> rules, String key, String subjectType, Collection<Long> subjectIds) {
        List<Permission> matching = matching(rules, key, subjectType, subjectIds);
        return matching.isEmpty() ? Optional.empty() : Optional.of(decider(matching));
    }

    private static List<Permission> matching(
            List<Permission> rules, String key, String subjectType, Collection<Long> subjectIds) {
        if (subjectIds.isEmpty()) {
            return List.of();
        }
        return rules.stream()
                .filter(rule -> subjectType.equals(rule.getSubjectType()))
                .filter(rule -> subjectIds.contains(rule.getSubjectId()))
                .filter(rule -> rule.getKanbanPermission() != null
                        && key.equals(rule.getKanbanPermission().getKey()))
                .sorted(REPORTING_ORDER)
                .toList();
    }

    private static Permission decider(List<Permission> matching) {
        return matching.stream()
                .filter(rule -> isDeny(rule.getState()))
                .findFirst()
                .orElse(matching.get(0));
    }

    private static Collection<Long> subjectIds(PermissionSnapshot snapshot, String tier) {
        return switch (tier) {
            // No one in particular when checking what a set of roles allows.
            case SUBJECT_USER -> snapshot.userId() == null ? Set.of() : Set.of(snapshot.userId());
            case SUBJECT_ROLE -> snapshot.member() ? snapshot.roleIds() : Set.of();
            default -> snapshot.member() ? snapshot.discordFlagBits() : Set.of();
        };
    }

    /** Anything other than an explicit ALLOW counts as a DENY. */
    private static boolean isDeny(String state) {
        return state == null || !STATE_ALLOW.equals(state.toUpperCase(Locale.ROOT));
    }

    private static PermissionEvaluationService.Decision toDecision(Permission rule, String tier) {
        return new PermissionEvaluationService.Decision(
                !isDeny(rule.getState()), tier, rule.getScopeType(), rule.getScopeId(), rule.getId());
    }
}
