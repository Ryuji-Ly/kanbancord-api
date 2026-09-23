package com.kanbancord_api.service;

import com.kanbancord_api.model.Permission;
import com.kanbancord_api.model.Role;
import com.kanbancord_api.model.ServerMember;
import com.kanbancord_api.permission.DiscordPermissionFlag;
import com.kanbancord_api.permission.DiscordPermissionParser;
import com.kanbancord_api.permission.PermissionRank;
import com.kanbancord_api.repository.MemberRoleRepository;
import com.kanbancord_api.repository.PermissionRepository;
import com.kanbancord_api.repository.ServerMemberRepository;
import com.kanbancord_api.repository.ServerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads a {@link PermissionSnapshot} with a fixed handful of queries (membership, roles, owner,
 * server rules, board rules) and resolves keys in memory via {@link PermissionResolver}.
 */
@Service
@Transactional(readOnly = true)
public class PermissionEvaluationService {

    private static final String SCOPE_SERVER = "SERVER";
    private static final String SCOPE_BOARD = "BOARD";

    private final PermissionRepository permissionRepository;
    private final ServerMemberRepository serverMemberRepository;
    private final MemberRoleRepository memberRoleRepository;
    private final ServerRepository serverRepository;
    private final DiscordPermissionParser discordPermissionParser;

    public PermissionEvaluationService(
            PermissionRepository permissionRepository,
            ServerMemberRepository serverMemberRepository,
            MemberRoleRepository memberRoleRepository,
            ServerRepository serverRepository,
            DiscordPermissionParser discordPermissionParser) {
        this.permissionRepository = permissionRepository;
        this.serverMemberRepository = serverMemberRepository;
        this.memberRoleRepository = memberRoleRepository;
        this.serverRepository = serverRepository;
        this.discordPermissionParser = discordPermissionParser;
    }

    public boolean isAllowed(Long serverId, Long boardId, Long userId, String kanbanPermissionKey) {
        return resolve(serverId, boardId, userId, kanbanPermissionKey).allowed();
    }

    public Decision resolve(Long serverId, Long boardId, Long userId, String kanbanPermissionKey) {
        return PermissionResolver.resolve(loadSnapshot(serverId, boardId, userId), kanbanPermissionKey);
    }

    /**
     * Resolves several keys against a single snapshot.
     */
    public Map<String, Decision> resolveAll(Long serverId, Long boardId, Long userId, Collection<String> keys) {
        PermissionSnapshot snapshot = loadSnapshot(serverId, boardId, userId);
        Map<String, Decision> decisions = new LinkedHashMap<>();
        for (String key : keys) {
            decisions.put(key, PermissionResolver.resolve(snapshot, key));
        }
        return decisions;
    }

    /**
     * Resolves one key for many boards of the same server, loading membership and server rules once
     * and all board rules in a single query.
     *
     * @return the ids of the boards on which the key is allowed
     */
    public Set<Long> filterAllowedBoards(Long serverId, Collection<Long> boardIds, Long userId,
            String kanbanPermissionKey) {
        if (boardIds.isEmpty()) {
            return Set.of();
        }

        PermissionSnapshot serverSnapshot = loadSnapshot(serverId, null, userId);
        Map<Long, List<Permission>> rulesByBoard = permissionRepository
                .findByScopeTypeAndScopeIdIn(SCOPE_BOARD, boardIds)
                .stream()
                .collect(Collectors.groupingBy(Permission::getScopeId));

        return boardIds.stream()
                .filter(boardId -> PermissionResolver.resolve(
                        serverSnapshot.withBoardRules(rulesByBoard.getOrDefault(boardId, List.of())),
                        kanbanPermissionKey).allowed())
                .collect(Collectors.toSet());
    }

    /**
     * The user's highest allowed catalog rank, evaluated at board scope when {@code boardId} is given.
     */
    public PermissionRank calculateEffectiveRank(Long serverId, Long boardId, Long userId) {
        return PermissionResolver.effectiveRank(loadSnapshot(serverId, boardId, userId));
    }

    public PermissionSnapshot loadSnapshot(Long serverId, Long boardId, Long userId) {
        Optional<ServerMember> member = serverMemberRepository.findByServer_ServerIdAndUser_UserId(serverId, userId);

        Set<Long> roleIds = Set.of();
        Set<Long> discordFlagBits = Set.of();
        if (member.isPresent()) {
            List<Role> roles = memberRoleRepository.findRolesByServerMemberId(member.get().getId());
            roleIds = roles.stream().map(Role::getRoleId).collect(Collectors.toUnmodifiableSet());

            long aggregatedDiscordPermissions = 0L;
            for (Role role : roles) {
                if (role.getDiscordPermissions() != null) {
                    aggregatedDiscordPermissions |= role.getDiscordPermissions();
                }
            }

            Set<DiscordPermissionFlag> flags = discordPermissionParser.parse(aggregatedDiscordPermissions);
            boolean isServerOwner = serverRepository.findOwnerIdByServerId(serverId)
                    .map(userId::equals)
                    .orElse(false);
            if (isServerOwner) {
                flags.add(DiscordPermissionFlag.ADMINISTRATOR);
            }
            discordFlagBits = flags.stream()
                    .map(DiscordPermissionFlag::getBit)
                    .collect(Collectors.toUnmodifiableSet());
        }

        List<Permission> serverRules = permissionRepository
                .findByScopeTypeAndScopeIdOrderByPriorityDescIdDesc(SCOPE_SERVER, serverId);
        List<Permission> boardRules = boardId == null
                ? List.of()
                : permissionRepository.findByScopeTypeAndScopeIdOrderByPriorityDescIdDesc(SCOPE_BOARD, boardId);

        return new PermissionSnapshot(
                userId,
                member.isPresent(),
                roleIds,
                discordFlagBits,
                boardRules,
                serverRules);
    }

    public record Decision(boolean allowed, String sourceTier, String sourceScopeType, Long sourceScopeId,
            Long sourcePermissionId) {

        public static final Decision NONE = new Decision(false, "NONE", null, null, null);
    }
}
