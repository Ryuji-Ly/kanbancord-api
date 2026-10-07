package com.kanbancord_api.server;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.LocalDateTime;

public record MemberRoleResponse(
        Long id,
        Long serverMemberId,
        @JsonSerialize(using = ToStringSerializer.class) Long roleId,
        @JsonSerialize(using = ToStringSerializer.class) Long serverId,
        @JsonSerialize(using = ToStringSerializer.class) Long userId,
        LocalDateTime assignedAt) {
}
