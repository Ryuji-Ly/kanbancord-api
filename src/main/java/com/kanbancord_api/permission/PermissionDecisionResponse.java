package com.kanbancord_api.permission;


public record PermissionDecisionResponse(
        boolean allowed,
        String sourceTier,
        String sourceScopeType,
        Long sourceScopeId,
        Long sourcePermissionId) {

    public static PermissionDecisionResponse from(PermissionEvaluationService.Decision decision) {
        return new PermissionDecisionResponse(
                decision.allowed(),
                decision.sourceTier(),
                decision.sourceScopeType(),
                decision.sourceScopeId(),
                decision.sourcePermissionId());
    }
}
