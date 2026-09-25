package com.kanbancord_api.feature;

import com.kanbancord_api.exception.FeatureDisabledException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which optional features each server has switched on. Checked on many requests, so the sets are
 * cached; a change evicts its server once it has committed.
 */
@Service
public class ServerFeatureService {

    private final JdbcTemplate jdbcTemplate;
    private final Map<Long, Set<Feature>> cache = new ConcurrentHashMap<>();

    public ServerFeatureService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Published inside the transaction that changed a server's features. */
    public record FeaturesChanged(Long serverId) {
    }

    public Set<Feature> enabled(Long serverId) {
        return cache.computeIfAbsent(serverId, this::load);
    }

    public boolean isEnabled(Long serverId, Feature feature) {
        return enabled(serverId).contains(feature);
    }

    /** Refuses the request with 409 when the server has the feature switched off. */
    public void require(Long serverId, Feature feature) {
        if (!isEnabled(serverId, feature)) {
            throw new FeatureDisabledException(feature.label());
        }
    }

    /** Every feature and whether it is on, in declaration order. */
    public Map<Feature, Boolean> all(Long serverId) {
        Set<Feature> enabled = enabled(serverId);
        Map<Feature, Boolean> all = new EnumMap<>(Feature.class);
        for (Feature feature : Feature.values()) {
            all.put(feature, enabled.contains(feature));
        }
        return all;
    }

    /** Replaces the server's features with exactly {@code enabled}. */
    @Transactional
    public void set(Long serverId, Set<Feature> enabled) {
        jdbcTemplate.update("DELETE FROM server_features WHERE server_id = ?", serverId);
        for (Feature feature : enabled) {
            jdbcTemplate.update("INSERT INTO server_features (server_id, feature) VALUES (?, ?)", serverId, feature.name());
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onFeaturesChanged(FeaturesChanged event) {
        cache.remove(event.serverId());
    }

    private Set<Feature> load(Long serverId) {
        Set<Feature> enabled = EnumSet.noneOf(Feature.class);
        for (String name : jdbcTemplate.queryForList(
                "SELECT feature FROM server_features WHERE server_id = ?", String.class, serverId)) {
            try {
                enabled.add(Feature.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // A feature this version does not know; ignore it rather than fail.
            }
        }
        return Collections.unmodifiableSet(enabled);
    }
}
