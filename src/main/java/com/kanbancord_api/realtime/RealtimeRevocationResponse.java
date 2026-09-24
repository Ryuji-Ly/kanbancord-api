package com.kanbancord_api.realtime;

/**
 * Sent on the session queue when the server ends one of the client's subscriptions, so the page can
 * stop showing data the user may no longer see.
 *
 * @param reason ACCESS_LOST when the user can no longer view it, BOARD_DELETED when its board is gone
 */
public record RealtimeRevocationResponse(
        String type,
        String reason,
        String destination,
        Long serverId,
        Long boardId) {
}
