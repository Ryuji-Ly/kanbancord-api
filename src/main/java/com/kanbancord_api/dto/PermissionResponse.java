package com.kanbancord_api.dto;

import java.time.LocalDateTime;

public class PermissionResponse {

    private Long id;
    private String scopeType;
    private Long scopeId;
    private String subjectType;
    private Long subjectId;
    private Integer kanbanPermissionId;
    private String kanbanPermissionKey;
    private String state;
    private Integer priority;
    private Boolean isImmutable;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public Long getScopeId() {
        return scopeId;
    }

    public void setScopeId(Long scopeId) {
        this.scopeId = scopeId;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public void setSubjectType(String subjectType) {
        this.subjectType = subjectType;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Long subjectId) {
        this.subjectId = subjectId;
    }

    public Integer getKanbanPermissionId() {
        return kanbanPermissionId;
    }

    public void setKanbanPermissionId(Integer kanbanPermissionId) {
        this.kanbanPermissionId = kanbanPermissionId;
    }

    public String getKanbanPermissionKey() {
        return kanbanPermissionKey;
    }

    public void setKanbanPermissionKey(String kanbanPermissionKey) {
        this.kanbanPermissionKey = kanbanPermissionKey;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Boolean getIsImmutable() {
        return isImmutable;
    }

    public void setIsImmutable(Boolean isImmutable) {
        this.isImmutable = isImmutable;
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
}
