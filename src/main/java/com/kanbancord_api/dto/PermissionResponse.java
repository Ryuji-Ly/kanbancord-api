package com.kanbancord_api.dto;

import com.kanbancord_api.model.Permission;
import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public class PermissionResponse {

    private Long id;
    private String scopeType;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long scopeId;
    private String subjectType;
    @JsonSerialize(using = ToStringSerializer.class)
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

    public static PermissionResponse from(Permission permission) {
        PermissionResponse response = new PermissionResponse();
        response.setId(permission.getId());
        response.setScopeType(permission.getScopeType());
        response.setScopeId(permission.getScopeId());
        response.setSubjectType(permission.getSubjectType());
        response.setSubjectId(permission.getSubjectId());

        if (permission.getKanbanPermission() != null) {
            response.setKanbanPermissionId(permission.getKanbanPermission().getPermissionId());
            response.setKanbanPermissionKey(permission.getKanbanPermission().getKey());
        }

        response.setState(permission.getState());
        response.setPriority(permission.getPriority());
        response.setIsImmutable(permission.getIsImmutable());
        response.setCreatedAt(permission.getCreatedAt());
        response.setUpdatedAt(permission.getUpdatedAt());
        return response;
    }
}
