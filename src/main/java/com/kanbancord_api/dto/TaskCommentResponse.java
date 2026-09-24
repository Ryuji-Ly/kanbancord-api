package com.kanbancord_api.dto;

import com.kanbancord_api.model.TaskComment;
import com.kanbancord_api.model.TaskCommentEdit;
import com.kanbancord_api.model.User;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.util.List;

public class TaskCommentResponse {
    private Long commentId;
    private Long taskId;
    private Long userId;
    private String authorUsername;
    private String authorGlobalName;
    private String authorAvatarUrl;
    private String content;
    private Long replyToId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
    private List<TaskCommentEditorResponse> editedByUsers;

    // Getters and Setters
    public Long getCommentId() {
        return commentId;
    }

    public void setCommentId(Long commentId) {
        this.commentId = commentId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getAuthorUsername() {
        return authorUsername;
    }

    public void setAuthorUsername(String authorUsername) {
        this.authorUsername = authorUsername;
    }

    public String getAuthorGlobalName() {
        return authorGlobalName;
    }

    public void setAuthorGlobalName(String authorGlobalName) {
        this.authorGlobalName = authorGlobalName;
    }

    public String getAuthorAvatarUrl() {
        return authorAvatarUrl;
    }

    public void setAuthorAvatarUrl(String authorAvatarUrl) {
        this.authorAvatarUrl = authorAvatarUrl;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getReplyToId() {
        return replyToId;
    }

    public void setReplyToId(Long replyToId) {
        this.replyToId = replyToId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public List<TaskCommentEditorResponse> getEditedByUsers() {
        return editedByUsers;
    }

    public void setEditedByUsers(List<TaskCommentEditorResponse> editedByUsers) {
        this.editedByUsers = editedByUsers;
    }

    public static TaskCommentResponse from(TaskComment comment) {
        TaskCommentResponse response = new TaskCommentResponse();
        response.setCommentId(comment.getCommentId());
        response.setTaskId(comment.getTask().getTaskId());
        response.setUserId(comment.getUser().getUserId());
        response.setAuthorUsername(comment.getUser().getUsername());
        response.setAuthorGlobalName(comment.getUser().getGlobalName());
        response.setAuthorAvatarUrl(comment.getUser().getAvatarUrl());
        response.setContent(comment.getContent());
        if (comment.getReplyTo() != null) {
            response.setReplyToId(comment.getReplyTo().getCommentId());
        }
        response.setCreatedAt(comment.getCreatedAt());
        response.setUpdatedAt(comment.getUpdatedAt());
        response.setDeletedAt(comment.getDeletedAt());

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
        response.setEditedByUsers(editors);
        return response;
    }
}
