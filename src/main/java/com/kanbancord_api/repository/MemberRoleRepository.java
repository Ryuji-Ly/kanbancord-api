package com.kanbancord_api.repository;

import com.kanbancord_api.model.MemberRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MemberRoleRepository extends JpaRepository<MemberRole, Long> {

    List<MemberRole> findByServerMember_Id(Long serverMemberId);

    List<MemberRole> findByRole_RoleId(Long roleId);

    Optional<MemberRole> findByServerMember_IdAndRole_RoleId(Long serverMemberId, Long roleId);

    void deleteByServerMember_IdAndRole_RoleId(Long serverMemberId, Long roleId);

    @Modifying
    @Query("DELETE FROM MemberRole mr WHERE mr.serverMember.id = :serverMemberId")
    void deleteByServerMember_Id(@Param("serverMemberId") Long serverMemberId);
}
