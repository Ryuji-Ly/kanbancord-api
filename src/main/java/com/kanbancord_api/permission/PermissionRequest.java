package com.kanbancord_api.permission;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public class PermissionRequest {

    @NotBlank(message = "Scope type is required")
    @Pattern(regexp = "^(SERVER|BOARD)$", message = "Scope type must be SERVER or BOARD")
    private String scopeType;

    @NotNull(message = "Scope ID is required")
    private Long scopeId;

    @NotBlank(message = "Subject type is required")
    @Pattern(regexp = "^(USER|ROLE|DISCORD_PERMISSION)$", message = "Subject type must be USER, ROLE, or DISCORD_PERMISSION")
    private String subjectType;

    @NotNull(message = "Subject ID is required")
    private Long subjectId;

    @NotNull(message = "Kanban permission ID is required")
    private Integer kanbanPermissionId;

    @NotBlank(message = "State is required")
    @Pattern(regexp = "^(ALLOW|DENY)$", message = "State must be ALLOW or DENY")
    private String state;

    @NotNull(message = "Priority is required")
    @Min(value = 0, message = "Priority must be at least 0")
    @Max(value = 1000, message = "Priority must be at most 1000")
    private Integer priority;

    private Boolean isImmutable;

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
}
