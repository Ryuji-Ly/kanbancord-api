package com.kanbancord_api.session;

import java.util.Set;
import java.util.UUID;

/** Sessions that were just signed out; published inside the transaction that revoked them. */
public record SessionsRevokedEvent(Long userId, Set<UUID> sessionIds) {
}
