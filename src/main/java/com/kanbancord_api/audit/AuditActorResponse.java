package com.kanbancord_api.audit;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kanbancord_api.user.User;

/** Someone who has made a change recorded in the audit log. */
public record AuditActorResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long userId,
        String username,
        String displayName,
        String avatarUrl) {

    static AuditActorResponse from(User user) {
        String displayName = user.getGlobalName() != null && !user.getGlobalName().isBlank()
                ? user.getGlobalName()
                : user.getUsername();
        return new AuditActorResponse(user.getUserId(), user.getUsername(), displayName, user.getAvatarUrl());
    }
}
