package com.kanbancord_api.dto;

import com.kanbancord_api.service.PermissionEvaluationService;

public class PermissionDecisionResponse {

    private boolean allowed;
    private String sourceTier;
    private String sourceScopeType;
    private Long sourceScopeId;
    private Long sourcePermissionId;

    public boolean isAllowed() {
        return allowed;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
    }

    public String getSourceTier() {
        return sourceTier;
    }

    public void setSourceTier(String sourceTier) {
        this.sourceTier = sourceTier;
    }

    public String getSourceScopeType() {
        return sourceScopeType;
    }

    public void setSourceScopeType(String sourceScopeType) {
        this.sourceScopeType = sourceScopeType;
    }

    public Long getSourceScopeId() {
        return sourceScopeId;
    }

    public void setSourceScopeId(Long sourceScopeId) {
        this.sourceScopeId = sourceScopeId;
    }

    public Long getSourcePermissionId() {
        return sourcePermissionId;
    }

    public void setSourcePermissionId(Long sourcePermissionId) {
        this.sourcePermissionId = sourcePermissionId;
    }

    public static PermissionDecisionResponse from(PermissionEvaluationService.Decision decision) {
        PermissionDecisionResponse response = new PermissionDecisionResponse();
        response.setAllowed(decision.allowed());
        response.setSourceTier(decision.sourceTier());
        response.setSourceScopeType(decision.sourceScopeType());
        response.setSourceScopeId(decision.sourceScopeId());
        response.setSourcePermissionId(decision.sourcePermissionId());
        return response;
    }
}
