package com.kanbancord_api.service;

import com.kanbancord_api.model.MemberRole;
import com.kanbancord_api.model.ServerMember;
import com.kanbancord_api.repository.MemberRoleRepository;
import com.kanbancord_api.repository.RoleRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class MemberRoleService {

    private final MemberRoleRepository memberRoleRepository;
    private final RoleRepository roleRepository;
    private final EntityManager entityManager;

    public MemberRoleService(MemberRoleRepository memberRoleRepository, RoleRepository roleRepository,
            EntityManager entityManager) {
        this.memberRoleRepository = memberRoleRepository;
        this.roleRepository = roleRepository;
        this.entityManager = entityManager;
    }

    public MemberRole create(MemberRole memberRole) {
        return memberRoleRepository.save(memberRole);
    }

    @Transactional(readOnly = true)
    public Optional<MemberRole> findById(Long id) {
        return memberRoleRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<MemberRole> findAll() {
        return memberRoleRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<MemberRole> findByServerMemberId(Long serverMemberId) {
        return memberRoleRepository.findByServerMember_Id(serverMemberId);
    }

    @Transactional(readOnly = true)
    public Optional<MemberRole> findByServerMemberIdAndRoleId(Long serverMemberId, Long roleId) {
        return memberRoleRepository.findByServerMember_IdAndRole_RoleId(serverMemberId, roleId);
    }

    public MemberRole update(MemberRole memberRole) {
        return memberRoleRepository.save(memberRole);
    }

    public void deleteById(Long id) {
        memberRoleRepository.deleteById(id);
    }

    /**
     * Atomically replaces all role assignments for a member.
     * Roles not present in the DB (e.g. managed/bot roles filtered during sync) are
     * silently skipped. The whole operation runs in a single transaction.
     */
    public void replaceForMember(ServerMember member, List<Long> roleIds) {
        memberRoleRepository.deleteByServerMember_Id(member.getId());
        entityManager.flush(); // force DELETE to DB before inserts
        entityManager.clear(); // evict stale state from first-level cache
        for (Long roleId : roleIds) {
            roleRepository.findById(roleId).ifPresent(role -> {
                MemberRole mr = new MemberRole();
                mr.setServerMember(member);
                mr.setRole(role);
                memberRoleRepository.save(mr);
            });
        }
    }
}
