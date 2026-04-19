package com.kanbancord_api.controller;

import com.kanbancord_api.dto.MemberRoleRequest;
import com.kanbancord_api.dto.MemberRoleResponse;
import com.kanbancord_api.model.MemberRole;
import com.kanbancord_api.model.Role;
import com.kanbancord_api.model.ServerMember;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.MemberRoleService;
import com.kanbancord_api.service.ResourceValidator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

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

    @PostMapping
    public ResponseEntity<MemberRoleResponse> createMemberRole(
            @PathVariable Long serverId,
            @PathVariable Long memberId,
            @RequestParam Long userId,
            @Valid @RequestBody MemberRoleRequest request) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.validatePathMatchesRequestId("serverMemberId", memberId, request.getServerMemberId());

        ServerMember member = resourceValidator.requireServerMemberInServer(memberId, serverId);
        Role role = resourceValidator.requireRoleInServer(request.getRoleId(), serverId);

        memberRoleService.findByServerMemberIdAndRoleId(memberId, request.getRoleId()).ifPresent(existing -> {
            throw new IllegalStateException("Role is already assigned to this member");
        });

        MemberRole memberRole = new MemberRole();
        memberRole.setServerMember(member);
        memberRole.setRole(role);

        MemberRole created = memberRoleService.create(memberRole);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @GetMapping
    public ResponseEntity<List<MemberRoleResponse>> getMemberRoles(
            @PathVariable Long serverId,
            @PathVariable Long memberId,
            @RequestParam Long userId) {

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
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.requireServerMemberInServer(memberId, serverId);

        MemberRole memberRole = resourceValidator.requireMemberRoleInServer(memberRoleId, serverId);
        resourceValidator.validatePathMatchesRequestId("serverMemberId", memberId,
                memberRole.getServerMember().getId());

        return ResponseEntity.ok(toResponse(memberRole));
    }

    @PutMapping("/{memberRoleId}")
    public ResponseEntity<MemberRoleResponse> updateMemberRole(
            @PathVariable Long serverId,
            @PathVariable Long memberId,
            @PathVariable Long memberRoleId,
            @RequestParam Long userId,
            @Valid @RequestBody MemberRoleRequest request) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.validatePathMatchesRequestId("serverMemberId", memberId, request.getServerMemberId());

        MemberRole memberRole = resourceValidator.requireMemberRoleInServer(memberRoleId, serverId);
        resourceValidator.validatePathMatchesRequestId("serverMemberId", memberId,
                memberRole.getServerMember().getId());

        ServerMember member = resourceValidator.requireServerMemberInServer(memberId, serverId);
        Role role = resourceValidator.requireRoleInServer(request.getRoleId(), serverId);

        memberRole.setServerMember(member);
        memberRole.setRole(role);

        MemberRole updated = memberRoleService.update(memberRole);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{memberRoleId}")
    public ResponseEntity<Void> deleteMemberRole(
            @PathVariable Long serverId,
            @PathVariable Long memberId,
            @PathVariable Long memberRoleId,
            @RequestParam Long userId) {

        accessValidator.requireUserInServer(userId, serverId);
        resourceValidator.requireServerMemberInServer(memberId, serverId);

        MemberRole memberRole = resourceValidator.requireMemberRoleInServer(memberRoleId, serverId);
        resourceValidator.validatePathMatchesRequestId("serverMemberId", memberId,
                memberRole.getServerMember().getId());

        memberRoleService.deleteById(memberRole.getId());
        return ResponseEntity.noContent().build();
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
