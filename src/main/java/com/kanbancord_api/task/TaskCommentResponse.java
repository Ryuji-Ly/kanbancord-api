package com.kanbancord_api.task;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kanbancord_api.user.User;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.util.List;

public record TaskCommentResponse(
        Long commentId,
        Long taskId,
        @JsonSerialize(using = ToStringSerializer.class) Long userId,
        String authorUsername,
        String authorGlobalName,
        String authorAvatarUrl,
        String content,
        Long replyToId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt,
        List<TaskCommentEditorResponse> editedByUsers) {

    public static TaskCommentResponse from(TaskComment comment) {
        // Each editor once, in the order of their first edit.
        List<TaskCommentEditorResponse> editors = comment.getEdits().stream()
                .sorted(Comparator.comparing(TaskCommentEdit::getEditedAt))
                .map(TaskCommentEdit::getEditor)
                .collect(Collectors.toMap(User::getUserId, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new))
                .values()
                .stream()
                .map(TaskCommentEditorResponse::from)
                .collect(Collectors.toList());
        return new TaskCommentResponse(
                comment.getCommentId(),
                comment.getTask().getTaskId(),
                comment.getUser().getUserId(),
                comment.getUser().getUsername(),
                comment.getUser().getGlobalName(),
                comment.getUser().getAvatarUrl(),
                comment.getContent(),
                comment.getReplyTo() == null ? null : comment.getReplyTo().getCommentId(),
                comment.getCreatedAt(),
                comment.getUpdatedAt(),
                comment.getDeletedAt(),
                editors);
    }
}
