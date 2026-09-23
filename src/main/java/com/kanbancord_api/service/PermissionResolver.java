package com.kanbancord_api.service;

import com.kanbancord_api.model.Permission;
import com.kanbancord_api.permission.KanbanPermissionCatalog;
import com.kanbancord_api.permission.PermissionRank;

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
 */
public final class PermissionResolver {

    public static final String ADMIN_KEY = "ADMIN";

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
        if (subjectIds.isEmpty()) {
            return Optional.empty();
        }

        List<Permission> matching = rules.stream()
                .filter(rule -> subjectType.equals(rule.getSubjectType()))
                .filter(rule -> subjectIds.contains(rule.getSubjectId()))
                .filter(rule -> rule.getKanbanPermission() != null
                        && key.equals(rule.getKanbanPermission().getKey()))
                .sorted(REPORTING_ORDER)
                .toList();

        return matching.stream()
                .filter(rule -> isDeny(rule.getState()))
                .findFirst()
                .or(() -> matching.stream().findFirst());
    }

    private static Collection<Long> subjectIds(PermissionSnapshot snapshot, String tier) {
        return switch (tier) {
            case SUBJECT_USER -> Set.of(snapshot.userId());
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
