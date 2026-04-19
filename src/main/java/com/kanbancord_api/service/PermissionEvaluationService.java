package com.kanbancord_api.service;

import com.kanbancord_api.model.MemberRole;
import com.kanbancord_api.model.Permission;
import com.kanbancord_api.model.Role;
import com.kanbancord_api.model.ServerMember;
import com.kanbancord_api.permission.DiscordPermissionFlag;
import com.kanbancord_api.permission.DiscordPermissionParser;
import com.kanbancord_api.repository.PermissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class PermissionEvaluationService {

    private static final String SCOPE_SERVER = "SERVER";
    private static final String SCOPE_BOARD = "BOARD";
    private static final String SUBJECT_DISCORD_PERMISSION = "DISCORD_PERMISSION";
    private static final String SUBJECT_ROLE = "ROLE";
    private static final String SUBJECT_USER = "USER";
    private static final String STATE_ALLOW = "ALLOW";

    private final PermissionRepository permissionRepository;
    private final KanbanPermissionService kanbanPermissionService;
    private final ServerMemberService serverMemberService;
    private final MemberRoleService memberRoleService;
    private final RoleService roleService;
    private final DiscordPermissionParser discordPermissionParser;

    public PermissionEvaluationService(
            PermissionRepository permissionRepository,
            KanbanPermissionService kanbanPermissionService,
            ServerMemberService serverMemberService,
            MemberRoleService memberRoleService,
            RoleService roleService,
            DiscordPermissionParser discordPermissionParser) {
        this.permissionRepository = permissionRepository;
        this.kanbanPermissionService = kanbanPermissionService;
        this.serverMemberService = serverMemberService;
        this.memberRoleService = memberRoleService;
        this.roleService = roleService;
        this.discordPermissionParser = discordPermissionParser;
    }

    public boolean isAllowed(Long serverId, Long boardId, Long userId, String kanbanPermissionKey) {
        return resolve(serverId, boardId, userId, kanbanPermissionKey).allowed();
    }

    public Decision resolve(Long serverId, Long boardId, Long userId, String kanbanPermissionKey) {
        Integer permissionId = kanbanPermissionService.findByKey(kanbanPermissionKey)
                .map(item -> item.getPermissionId())
                .orElse(null);
        if (permissionId == null) {
            return new Decision(false, "NONE", null, null, null);
        }

        MembershipContext membershipContext = buildMembershipContext(serverId, userId);

        Permission userRule = findBestRule(
                scopePairs(serverId, boardId),
                SUBJECT_USER,
                List.of(userId),
                permissionId);
        if (userRule != null) {
            return toDecision(userRule, "USER");
        }

        Permission roleRule = findBestRule(
                scopePairs(serverId, boardId),
                SUBJECT_ROLE,
                membershipContext.roleIds(),
                permissionId);
        if (roleRule != null) {
            return toDecision(roleRule, "ROLE");
        }

        List<Long> discordSubjects = membershipContext.discordFlags().stream()
                .map(DiscordPermissionFlag::getBit)
                .toList();

        Permission discordRule = findBestRule(
                scopePairs(serverId, boardId),
                SUBJECT_DISCORD_PERMISSION,
                discordSubjects,
                permissionId);
        if (discordRule != null) {
            return toDecision(discordRule, "DISCORD_PERMISSION");
        }

        return new Decision(false, "NONE", null, null, null);
    }

    private MembershipContext buildMembershipContext(Long serverId, Long userId) {
        Optional<ServerMember> memberOptional = serverMemberService.findByServerIdAndUserId(serverId, userId);
        if (memberOptional.isEmpty()) {
            return new MembershipContext(List.of(), Set.of());
        }

        ServerMember member = memberOptional.get();
        List<Long> roleIds = memberRoleService.findByServerMemberId(member.getId()).stream()
                .map(MemberRole::getRole)
                .filter(role -> role != null)
                .map(Role::getRoleId)
                .toList();

        long aggregatedDiscordPermissions = 0L;
        for (Long roleId : roleIds) {
            Role role = roleService.findById(roleId).orElse(null);
            if (role == null || role.getDiscordPermissions() == null) {
                continue;
            }
            aggregatedDiscordPermissions |= role.getDiscordPermissions();
        }

        Set<DiscordPermissionFlag> flags = discordPermissionParser.parse(aggregatedDiscordPermissions);
        return new MembershipContext(roleIds, flags);
    }

    private List<ScopePair> scopePairs(Long serverId, Long boardId) {
        List<ScopePair> scopes = new ArrayList<>();
        if (boardId != null) {
            scopes.add(new ScopePair(SCOPE_BOARD, boardId, 0));
        }
        scopes.add(new ScopePair(SCOPE_SERVER, serverId, 1));
        return scopes;
    }

    private Permission findBestRule(
            List<ScopePair> scopes,
            String subjectType,
            List<Long> subjectIds,
            Integer kanbanPermissionId) {

        if (subjectIds == null || subjectIds.isEmpty()) {
            return null;
        }

        List<ScoredPermission> candidates = new ArrayList<>();
        for (ScopePair scope : scopes) {
            for (Long subjectId : subjectIds) {
                List<Permission> rules = permissionRepository
                        .findByScopeTypeAndScopeIdAndSubjectTypeAndSubjectIdOrderByPriorityDescIdDesc(
                                scope.scopeType(), scope.scopeId(), subjectType, subjectId)
                        .stream()
                        .filter(rule -> rule.getKanbanPermission() != null
                                && rule.getKanbanPermission().getPermissionId().equals(kanbanPermissionId))
                        .toList();

                for (Permission rule : rules) {
                    candidates.add(new ScoredPermission(scope.scopeRank(), rule));
                }
            }
        }

        return candidates.stream()
                .sorted(Comparator
                        .comparingInt(ScoredPermission::scopeRank)
                        .thenComparing((ScoredPermission item) -> item.permission().getPriority(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing((ScoredPermission item) -> item.permission().getId(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .map(ScoredPermission::permission)
                .findFirst()
                .orElse(null);
    }

    private Decision toDecision(Permission permission, String tier) {
        boolean allow = permission.getState() != null
                && STATE_ALLOW.equals(permission.getState().toUpperCase(Locale.ROOT));
        return new Decision(
                allow,
                tier,
                permission.getScopeType(),
                permission.getScopeId(),
                permission.getId());
    }

    private record ScopePair(String scopeType, Long scopeId, int scopeRank) {
    }

    private record ScoredPermission(int scopeRank, Permission permission) {
    }

    private record MembershipContext(List<Long> roleIds, Set<DiscordPermissionFlag> discordFlags) {
    }

    public record Decision(boolean allowed, String sourceTier, String sourceScopeType, Long sourceScopeId,
            Long sourcePermissionId) {
    }
}
