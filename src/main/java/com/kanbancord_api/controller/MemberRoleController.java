package com.kanbancord_api.controller;

import com.kanbancord_api.dto.MemberRoleResponse;
import com.kanbancord_api.model.MemberRole;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.MemberRoleService;
import com.kanbancord_api.service.ResourceValidator;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Read-only view of a member's Discord roles. Role membership is owned by Discord and synced by the
 * bot through the internal sync API; it is never written by clients.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/members/{memberId}/roles")
@Validated
public class MemberRoleController {

    private final MemberRoleService memberRoleService;
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;

    public MemberRoleController(
            MemberRoleService memberRoleService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator) {
        this.memberRoleService = memberRoleService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
    }

    @GetMapping
    public ResponseEntity<List<MemberRoleResponse>> getMemberRoles(
            @PathVariable Long serverId,
            @PathVariable Long memberId,
            @CurrentUser Long userId) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.requireServerMemberInServer(memberId, serverId);

        List<MemberRoleResponse> responses = memberRoleService.findByServerMemberId(memberId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{memberRoleId}")
    public ResponseEntity<MemberRoleResponse> getMemberRoleById(
            @PathVariable Long serverId,
            @PathVariable Long memberId,
            @PathVariable Long memberRoleId,
            @CurrentUser Long userId) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.requireServerMemberInServer(memberId, serverId);

        MemberRole memberRole = resourceValidator.requireMemberRoleInServer(memberRoleId, serverId);
        resourceValidator.validatePathMatchesRequestId("serverMemberId", memberId,
                memberRole.getServerMember().getId());

        return ResponseEntity.ok(toResponse(memberRole));
    }

    private MemberRoleResponse toResponse(MemberRole memberRole) {
        MemberRoleResponse response = new MemberRoleResponse();
        response.setId(memberRole.getId());
        response.setServerMemberId(memberRole.getServerMember().getId());
        response.setRoleId(memberRole.getRole().getRoleId());
        response.setServerId(memberRole.getServerMember().getServer().getServerId());
        response.setUserId(memberRole.getServerMember().getUser().getUserId());
        response.setAssignedAt(memberRole.getAssignedAt());
        return response;
    }
}
