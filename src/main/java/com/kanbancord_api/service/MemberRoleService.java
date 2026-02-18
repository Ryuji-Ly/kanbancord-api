package com.kanbancord_api.service;

import com.kanbancord_api.model.MemberRole;
import com.kanbancord_api.repository.MemberRoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class MemberRoleService {

    private final MemberRoleRepository memberRoleRepository;

    public MemberRoleService(MemberRoleRepository memberRoleRepository) {
        this.memberRoleRepository = memberRoleRepository;
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
    public List<MemberRole> findByRoleId(Long roleId) {
        return memberRoleRepository.findByRole_RoleId(roleId);
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

    public void deleteByServerMemberIdAndRoleId(Long serverMemberId, Long roleId) {
        memberRoleRepository.deleteByServerMember_IdAndRole_RoleId(serverMemberId, roleId);
    }
}
