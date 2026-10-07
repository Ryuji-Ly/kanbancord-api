package com.kanbancord_api.task;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kanbancord_api.user.User;

public record TaskCommentEditorResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long userId,
        String username,
        String globalName,
        String avatarUrl) {

    public static TaskCommentEditorResponse from(User user) {
        return new TaskCommentEditorResponse(
                user.getUserId(),
                user.getUsername(),
                user.getGlobalName(),
                user.getAvatarUrl());
    }
}
