package com.kanbancord_api.event;

/**
 * A change that concerns one user only, such as their preferences or notifications. Announced after
 * it commits on that user's own realtime queue, reaching every tab and device they are signed in on.
 *
 * @param payload what the client needs to update without refetching, or null
 */
public record UserEvent(Type type, Long userId, Object payload) {

    public enum Type {
        /** The profile or preferences changed. */
        PROFILE_UPDATED,
        /** A notification was created, read or deleted. */
        NOTIFICATIONS_CHANGED,
        /** A session started or was signed out. */
        SESSIONS_CHANGED,
        /** What the user wants by direct message from the bot changed. */
        NOTIFICATION_SETTINGS_CHANGED
    }
}
