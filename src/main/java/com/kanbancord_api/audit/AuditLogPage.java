package com.kanbancord_api.audit;

import java.util.List;

/**
 * A page of audit entries, newest first.
 *
 * @param nextBefore pass as {@code before} to get the next (older) page; null when there is none
 */
public record AuditLogPage(List<AuditLogResponse> entries, Long nextBefore) {
}
