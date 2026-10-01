package com.kanbancord_api.permission;

import com.kanbancord_api.board.Board;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.server.MemberRoleRepository;
import com.kanbancord_api.server.Role;
import com.kanbancord_api.server.RoleRepository;
import com.kanbancord_api.server.ServerMember;
import com.kanbancord_api.server.ServerMemberRepository;
import com.kanbancord_api.server.ServerRepository;
import com.kanbancord_api.user.User;
import com.kanbancord_api.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What someone may do, and why: for a member as they are, a member with other roles, or anyone with a
 * given set of roles. Worked out with the same rules and resolver as every real check, and then traced
 * rule by rule, so it can never say something different from what happens.
 */
@Service
@Transactional(readOnly = true)
public class AccessCheckService {

    /** Why a key came out as it did. */
    public enum Reason { ADMIN, OPEN, RULE, NONE }

    /**
     * Who is checked. {@code userId} is null for a set of roles; {@code rolesChanged} when the member's
     * roles were swapped for others.
     */
    public record Subject(String userId, String name, boolean member, boolean owner, boolean rolesChanged,
                          List<RoleRef> roles, List<String> discordPermissions, boolean administrator,
                          RuleRef administratorRule) {
    }

    public record RoleRef(String roleId, String name, Integer color, boolean everyone) {
    }

    /**
     * A rule as it took part. {@code builtIn} for the defaults that apply while custom permissions are
     * off, which are not stored and have no id.
     */
    public record RuleRef(Long ruleId, String scope, String subjectType, String subjectId, String subjectName,
                          String state, boolean builtIn) {
    }

    /** One key: whether it is allowed, why, and the rules that matched but did not decide. */
    public record KeyCheck(String key, String name, String category, boolean allowed, Reason reason,
                           RuleRef decidedBy, List<RuleRef> overridden) {
    }

    public record AccessCheck(Subject subject, boolean customPermissions, boolean openPermissions, String boardId,
                              String boardName, List<KeyCheck> results) {
    }

    private final PermissionEvaluationService evaluation;
    private final ServerMemberRepository serverMemberRepository;
    private final MemberRoleRepository memberRoleRepository;
    private final RoleRepository roleRepository;
    private final ServerRepository serverRepository;
    private final UserRepository userRepository;
    private final DiscordPermissionParser discordPermissionParser;

    public AccessCheckService(PermissionEvaluationService evaluation, ServerMemberRepository serverMemberRepository,
                              MemberRoleRepository memberRoleRepository, RoleRepository roleRepository,
                              ServerRepository serverRepository, UserRepository userRepository,
                              DiscordPermissionParser discordPermissionParser) {
        this.evaluation = evaluation;
        this.serverMemberRepository = serverMemberRepository;
        this.memberRoleRepository = memberRoleRepository;
        this.roleRepository = roleRepository;
        this.serverRepository = serverRepository;
        this.userRepository = userRepository;
        this.discordPermissionParser = discordPermissionParser;
    }

    /**
     * @param userId  the member to check, or null for no one in particular (then {@code roleIds} is required)
     * @param roleIds the roles to check with instead of the member's own, or null for theirs; @everyone
     *                is always included
     * @param board   the board to check on, or null for the server (boards without rules of their own)
     */
    public AccessCheck check(Long serverId, Long userId, Collection<Long> roleIds, Board board) {
        if (userId == null && roleIds == null) {
            throw new BadRequestException("Choose a member or some roles to check");
        }
        Map<Long, Role> serverRoles = roleRepository.findByServer_ServerIdOrderByPositionAsc(serverId).stream()
                .collect(Collectors.toMap(Role::getRoleId, role -> role, (a, b) -> a, LinkedHashMap::new));

        User user = userId == null ? null : userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));
        Optional<ServerMember> membership = userId == null ? Optional.empty()
                : serverMemberRepository.findByServer_ServerIdAndUser_UserId(serverId, userId);
        boolean owner = userId != null && serverRepository.findOwnerIdByServerId(serverId)
                .map(userId::equals).orElse(false);

        List<Role> roles;
        boolean member;
        if (roleIds != null) {
            roles = new ArrayList<>();
            for (Long roleId : new LinkedHashSet<>(roleIds)) {
                Role role = serverRoles.get(roleId);
                if (role == null) {
                    throw new BadRequestException("This server has no role " + roleId);
                }
                roles.add(role);
            }
            Role everyone = serverRoles.get(serverId);
            if (everyone != null && !roles.contains(everyone)) {
                roles.add(everyone);
            }
            member = true;
        } else if (membership.isPresent()) {
            roles = memberRoleRepository.findRolesWithEveryone(membership.get().getId(), serverId);
            member = true;
        } else {
            roles = List.of();
            member = false;
        }

        long bits = 0L;
        for (Role role : roles) {
            if (role.getDiscordPermissions() != null) {
                bits |= role.getDiscordPermissions();
            }
        }
        Set<DiscordPermissionFlag> flags = EnumSet.noneOf(DiscordPermissionFlag.class);
        if (member) {
            flags.addAll(discordPermissionParser.parse(bits));
            if (owner) {
                flags.add(DiscordPermissionFlag.ADMINISTRATOR);
            }
        }

        Long boardId = board == null ? null : board.getBoardId();
        PermissionSnapshot snapshot = new PermissionSnapshot(
                userId,
                member,
                member ? roles.stream().map(Role::getRoleId).collect(Collectors.toUnmodifiableSet()) : Set.of(),
                flags.stream().map(DiscordPermissionFlag::getBit).collect(Collectors.toUnmodifiableSet()),
                evaluation.boardRules(serverId, boardId),
                evaluation.serverRules(serverId),
                serverRepository.findOpenPermissions(serverId).orElse(false));

        Names names = new Names(serverRoles, userRepository);
        RuleRef administratorRule = null;
        if (PermissionResolver.resolve(snapshot, PermissionResolver.ADMIN_KEY).allowed()) {
            List<PermissionResolver.Layer> layers = PermissionResolver.trace(snapshot, PermissionResolver.ADMIN_KEY);
            administratorRule = layers.isEmpty() ? null : names.ref(layers.get(layers.size() - 1).decider());
        }

        List<KeyCheck> results = new ArrayList<>();
        for (KanbanPermissionCatalog item : KanbanPermissionCatalog.values()) {
            if (item.getKey().equals(PermissionResolver.ADMIN_KEY)
                    || !(board == null ? item.isServerScopeAllowed() : item.isBoardScopeAllowed())) {
                continue;
            }
            results.add(explain(snapshot, item, administratorRule, names));
        }

        Subject subject = new Subject(
                userId == null ? null : String.valueOf(userId),
                user == null ? null : displayName(user),
                member,
                owner,
                userId != null && roleIds != null,
                roles.stream()
                        .sorted(Comparator.comparing((Role role) -> role.getPosition() == null ? 0 : role.getPosition())
                                .reversed())
                        .map(role -> new RoleRef(String.valueOf(role.getRoleId()), role.getName(), role.getColor(),
                                role.getRoleId().equals(serverId)))
                        .toList(),
                relevantFlags(flags, snapshot),
                administratorRule != null,
                administratorRule);
        return new AccessCheck(subject, evaluation.customRulesApply(serverId), snapshot.openPermissions(),
                boardId == null ? null : String.valueOf(boardId), board == null ? null : board.getName(), results);
    }

    private static KeyCheck explain(PermissionSnapshot snapshot, KanbanPermissionCatalog item, RuleRef administratorRule,
                                    Names names) {
        String key = item.getKey();
        PermissionEvaluationService.Decision decision = PermissionResolver.resolve(snapshot, key);
        if (PermissionResolver.openlyAllowed(snapshot, key)) {
            return new KeyCheck(key, item.getName(), item.getCategory(), true, Reason.OPEN, null, List.of());
        }
        List<PermissionResolver.Layer> layers = PermissionResolver.trace(snapshot, key);
        List<RuleRef> matched = layers.stream()
                .flatMap(layer -> layer.rules().stream())
                .map(names::ref)
                .toList();
        if (PermissionResolver.ADMIN_KEY.equals(decision.sourceTier())) {
            return new KeyCheck(key, item.getName(), item.getCategory(), true, Reason.ADMIN, administratorRule, matched);
        }
        if (layers.isEmpty()) {
            return new KeyCheck(key, item.getName(), item.getCategory(), false, Reason.NONE, null, List.of());
        }
        Permission decider = layers.get(layers.size() - 1).decider();
        List<RuleRef> overridden = layers.stream()
                .flatMap(layer -> layer.rules().stream())
                .filter(rule -> rule != decider)
                .map(names::ref)
                .toList();
        return new KeyCheck(key, item.getName(), item.getCategory(), decision.allowed(), Reason.RULE,
                names.ref(decider), overridden);
    }

    /** The Discord permissions held that some rule here mentions: the ones that make a difference. */
    private static List<String> relevantFlags(Set<DiscordPermissionFlag> flags, PermissionSnapshot snapshot) {
        Set<Long> mentioned = new java.util.HashSet<>();
        for (Permission rule : snapshot.serverRules()) {
            if (PermissionResolver.SUBJECT_DISCORD_PERMISSION.equals(rule.getSubjectType())) {
                mentioned.add(rule.getSubjectId());
            }
        }
        for (Permission rule : snapshot.boardRules()) {
            if (PermissionResolver.SUBJECT_DISCORD_PERMISSION.equals(rule.getSubjectType())) {
                mentioned.add(rule.getSubjectId());
            }
        }
        return Arrays.stream(DiscordPermissionFlag.values())
                .filter(flags::contains)
                .filter(flag -> mentioned.contains(flag.getBit()) || flag == DiscordPermissionFlag.ADMINISTRATOR)
                .map(Enum::name)
                .toList();
    }

    private static String displayName(User user) {
        return user.getGlobalName() != null && !user.getGlobalName().isBlank() ? user.getGlobalName() : user.getUsername();
    }

    /** Names for the subjects of rules, looked up once each. */
    private static final class Names {
        private final Map<Long, Role> roles;
        private final UserRepository users;
        private final Map<Long, String> userNames = new HashMap<>();

        Names(Map<Long, Role> roles, UserRepository users) {
            this.roles = roles;
            this.users = users;
        }

        RuleRef ref(Permission rule) {
            return new RuleRef(rule.getId(), rule.getScopeType(), rule.getSubjectType(),
                    String.valueOf(rule.getSubjectId()), name(rule), rule.getState(), rule.getId() == null);
        }

        private String name(Permission rule) {
            Long id = rule.getSubjectId();
            return switch (rule.getSubjectType()) {
                case PermissionResolver.SUBJECT_ROLE -> Optional.ofNullable(roles.get(id)).map(Role::getName)
                        .orElse("a role that no longer exists");
                case PermissionResolver.SUBJECT_USER -> userNames.computeIfAbsent(id, userId -> users.findById(userId)
                        .map(AccessCheckService::displayName).orElse("someone"));
                default -> DiscordPermissionFlag.fromBit(id).map(Enum::name).orElse(String.valueOf(id));
            };
        }
    }
}
