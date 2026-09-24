package com.kanbancord_api.realtime;

import com.kanbancord_api.permission.PermissionEvaluationService;
import com.kanbancord_api.permission.PermissionResolver;
import com.kanbancord_api.permission.PermissionSnapshot;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether a user may currently receive events for a server or board.
 *
 * <p>Checked for every outgoing event, so a user who loses access stops receiving events without
 * reconnecting. Decisions are cached briefly to avoid a permission lookup per message; the cache is
 * cleared whenever a permission rule changes.
 */
@Service
public class RealtimeAccessCache {

    private static final String VIEW_SERVER = "VIEW_SERVER";
    private static final String VIEW_BOARD = "VIEW_BOARD";
    private static final int SWEEP_THRESHOLD = 10_000;

    private final PermissionEvaluationService permissionEvaluationService;
    private final Duration ttl;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    public RealtimeAccessCache(
            PermissionEvaluationService permissionEvaluationService,
            RealtimeProperties realtimeProperties) {
        this.permissionEvaluationService = permissionEvaluationService;
        this.ttl = Duration.ofSeconds(realtimeProperties.getAccessCacheTtlSeconds());
    }

    /** VIEW_BOARD on the board when {@code boardId} is given, VIEW_SERVER otherwise; members only. */
    public boolean canView(Long userId, Long serverId, Long boardId) {
        Instant now = Instant.now();
        Key key = new Key(userId, serverId, boardId);

        Entry cached = entries.get(key);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.allowed();
        }

        if (entries.size() > SWEEP_THRESHOLD) {
            entries.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
        }

        boolean allowed = evaluate(userId, serverId, boardId);
        entries.put(key, new Entry(allowed, now.plus(ttl)));
        return allowed;
    }

    public void invalidateAll() {
        entries.clear();
    }

    private boolean evaluate(Long userId, Long serverId, Long boardId) {
        // Evaluated without checking the board still exists, so viewers still hear about a board's deletion.
        PermissionSnapshot snapshot = permissionEvaluationService.loadSnapshot(serverId, boardId, userId);
        if (!snapshot.member()) {
            return false;
        }
        return PermissionResolver.resolve(snapshot, boardId == null ? VIEW_SERVER : VIEW_BOARD).allowed();
    }

    private record Key(Long userId, Long serverId, Long boardId) {
    }

    private record Entry(boolean allowed, Instant expiresAt) {
    }
}
