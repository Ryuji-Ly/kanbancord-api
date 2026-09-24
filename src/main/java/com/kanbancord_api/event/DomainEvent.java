package com.kanbancord_api.event;

/**
 * Something that changed, published inside the transaction that changed it.
 *
 * <p>Listeners record it in the audit log before the transaction commits and announce it over
 * realtime after it commits, so neither can disagree with what was stored.
 *
 * @param boardId the board the change belongs to, or null for server-level changes
 * @param before  the entity as a response DTO before the change; null when it was created
 * @param after   the entity as a response DTO after the change; null when it was deleted
 */
public record DomainEvent(
        EventType type,
        Long serverId,
        Long boardId,
        Long entityId,
        Long actorUserId,
        Object before,
        Object after) {

    public static DomainEvent created(EventType type, Long serverId, Long boardId, Long entityId, Long actorUserId,
            Object after) {
        return new DomainEvent(type, serverId, boardId, entityId, actorUserId, null, after);
    }

    public static DomainEvent changed(EventType type, Long serverId, Long boardId, Long entityId, Long actorUserId,
            Object before, Object after) {
        return new DomainEvent(type, serverId, boardId, entityId, actorUserId, before, after);
    }

    public static DomainEvent deleted(EventType type, Long serverId, Long boardId, Long entityId, Long actorUserId,
            Object before) {
        return new DomainEvent(type, serverId, boardId, entityId, actorUserId, before, null);
    }

    /** The entity's latest known state: after the change, or before it for deletions. */
    public Object snapshot() {
        return after != null ? after : before;
    }
}
