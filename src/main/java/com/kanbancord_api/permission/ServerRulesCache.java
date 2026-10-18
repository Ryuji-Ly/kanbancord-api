package com.kanbancord_api.permission;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Each server's stored server-scope rules, kept between requests: almost every request checks a
 * permission, and a server's rules (about a hundred) rarely change. Every change to them goes through
 * {@link PermissionService} or {@link PermissionBootstrapService}, which clear the server's entry
 * once their transaction has committed. Entries also expire after {@link #TTL}, for changes made
 * outside the API, such as by hand in the database.
 *
 * <p>A load that started before a change finished is not kept, so a request can never put back the
 * rules a change has just replaced. The rules are read-only copies, shared by every request.
 */
@Component
public class ServerRulesCache {

    static final Duration TTL = Duration.ofMinutes(10);

    private record Entry(List<Permission> rules, long loadedAt) {
    }

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    /** Bumped by every clearing: a load is only kept when the generation did not change while it ran. */
    private final AtomicLong generation = new AtomicLong();

    public List<Permission> get(Long serverId, Supplier<List<Permission>> load) {
        long now = System.currentTimeMillis();
        Entry entry = entries.get(serverId);
        if (entry != null && now - entry.loadedAt() < TTL.toMillis()) {
            return entry.rules();
        }
        long started = generation.get();
        List<Permission> rules = List.copyOf(load.get());
        if (generation.get() == started) {
            entries.put(serverId, new Entry(rules, now));
        }
        return rules;
    }

    /**
     * Forgets the server's rules once the current transaction commits (at once outside one), so other
     * requests never see the change before it is visible in the database, nor miss it after.
     */
    public void clearAfterCommit(Long serverId) {
        generation.incrementAndGet();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    clear(serverId);
                }
            });
        } else {
            clear(serverId);
        }
    }

    void clear(Long serverId) {
        generation.incrementAndGet();
        entries.remove(serverId);
    }
}
