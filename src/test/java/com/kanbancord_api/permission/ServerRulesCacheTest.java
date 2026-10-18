package com.kanbancord_api.permission;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerRulesCacheTest {

    private static Permission rule(long id) {
        Permission rule = new Permission();
        rule.setId(id);
        return rule;
    }

    @Test
    void keepsAServersRules_untilTheyAreCleared() {
        ServerRulesCache cache = new ServerRulesCache();
        AtomicInteger loads = new AtomicInteger();
        cache.get(1L, () -> {
            loads.incrementAndGet();
            return List.of(rule(1));
        });
        cache.get(1L, () -> {
            loads.incrementAndGet();
            return List.of(rule(2));
        });
        assertEquals(1, loads.get(), "the second request uses the kept rules");

        cache.clearAfterCommit(1L);
        assertEquals(2L, cache.get(1L, () -> List.of(rule(2))).get(0).getId(), "a change is seen at once");
        assertEquals(2L, cache.get(2L, () -> List.of(rule(2))).get(0).getId(), "servers are kept apart");
    }

    @Test
    void aLoadThatOverlapsAChange_isNotKept() {
        ServerRulesCache cache = new ServerRulesCache();
        // While the old rules are being read, a change commits and clears the server.
        List<Permission> stale = cache.get(1L, () -> {
            cache.clear(1L);
            return List.of(rule(1));
        });
        assertEquals(1L, stale.get(0).getId(), "the request itself still gets what it read");
        assertEquals(2L, cache.get(1L, () -> List.of(rule(2))).get(0).getId(), "but the next one reads again");
    }
}
