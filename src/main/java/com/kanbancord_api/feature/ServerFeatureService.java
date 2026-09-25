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
 * Which optional features each server has switched on, and which of those each board has switched
 * off for itself. Checked on many requests, so both are cached; a change evicts its server or board
 * once it has committed.
 */
@Service
public class ServerFeatureService {

    private final JdbcTemplate jdbcTemplate;
    private final Map<Long, Set<Feature>> cache = new ConcurrentHashMap<>();
    private final Map<Long, Set<Feature>> boardCache = new ConcurrentHashMap<>();

    public ServerFeatureService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Published inside the transaction that changed a server's features. */
    public record FeaturesChanged(Long serverId) {
    }

    /** Published inside the transaction that changed a board's features. */
    public record BoardFeaturesChanged(Long boardId) {
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

    /** The features on for a board: those the server has on, less those the board switched off. */
    public Set<Feature> enabled(Long serverId, Long boardId) {
        Set<Feature> disabled = disabledOnBoard(boardId);
        if (disabled.isEmpty()) {
            return enabled(serverId);
        }
        Set<Feature> enabled = EnumSet.noneOf(Feature.class);
        enabled.addAll(enabled(serverId));
        enabled.removeAll(disabled);
        return Collections.unmodifiableSet(enabled);
    }

    public boolean isEnabled(Long serverId, Long boardId, Feature feature) {
        return enabled(serverId, boardId).contains(feature);
    }

    /** Refuses the request with 409 when the server, or the board itself, has the feature switched off. */
    public void require(Long serverId, Long boardId, Feature feature) {
        if (!isEnabled(serverId, boardId, feature)) {
            throw new FeatureDisabledException(feature.label());
        }
    }

    /** The features the board has switched off for itself, whether or not the server has them on. */
    public Set<Feature> disabledOnBoard(Long boardId) {
        return boardCache.computeIfAbsent(boardId, this::loadBoard);
    }

    /** Replaces the features the board has switched off with exactly {@code disabled}. */
    @Transactional
    public void setDisabledOnBoard(Long boardId, Set<Feature> disabled) {
        jdbcTemplate.update("DELETE FROM board_disabled_features WHERE board_id = ?", boardId);
        for (Feature feature : disabled) {
            jdbcTemplate.update("INSERT INTO board_disabled_features (board_id, feature) VALUES (?, ?)",
                    boardId, feature.name());
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

    @TransactionalEventListener(fallbackExecution = true)
    public void onBoardFeaturesChanged(BoardFeaturesChanged event) {
        boardCache.remove(event.boardId());
    }

    private Set<Feature> load(Long serverId) {
        return features("SELECT feature FROM server_features WHERE server_id = ?", serverId);
    }

    private Set<Feature> loadBoard(Long boardId) {
        return features("SELECT feature FROM board_disabled_features WHERE board_id = ?", boardId);
    }

    private Set<Feature> features(String sql, Long id) {
        Set<Feature> features = EnumSet.noneOf(Feature.class);
        for (String name : jdbcTemplate.queryForList(sql, String.class, id)) {
            try {
                features.add(Feature.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // A feature this version does not know; ignore it rather than fail.
            }
        }
        return Collections.unmodifiableSet(features);
    }
}
