package com.kanbancord_api.repository;

import com.kanbancord_api.model.MemberRole;
import com.kanbancord_api.model.Role;
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

    @Query("SELECT r FROM MemberRole mr JOIN mr.role r WHERE mr.serverMember.id = :serverMemberId")
    List<Role> findRolesByServerMemberId(@Param("serverMemberId") Long serverMemberId);

    /**
     * The member's roles plus the server's @everyone role. Discord gives @everyone (whose id is the
     * server id) to every member implicitly, so it never appears in a member's role list.
     */
    @Query("SELECT r FROM Role r WHERE r.server.serverId = :serverId AND (r.roleId = :serverId OR r.roleId IN "
            + "(SELECT mr.role.roleId FROM MemberRole mr WHERE mr.serverMember.id = :serverMemberId))")
    List<Role> findRolesWithEveryone(@Param("serverMemberId") Long serverMemberId, @Param("serverId") Long serverId);

    Optional<MemberRole> findByServerMember_IdAndRole_RoleId(Long serverMemberId, Long roleId);

    @Modifying
    @Query("DELETE FROM MemberRole mr WHERE mr.serverMember.id = :serverMemberId")
    void deleteByServerMember_Id(@Param("serverMemberId") Long serverMemberId);
}
