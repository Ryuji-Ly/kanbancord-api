package com.kanbancord_api.feature;

import com.kanbancord_api.feature.FeatureSuggestionDetector.ColumnText;
import com.kanbancord_api.feature.FeatureSuggestionDetector.Finding;
import com.kanbancord_api.feature.FeatureSuggestionDetector.TaskText;
import com.kanbancord_api.permission.PermissionEvaluationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Suggests switching on a feature the board is doing by hand ({@link FeatureSuggestionDetector}), for features
 * that are off for the board and nobody dismissed there. Nothing is stored but the dismissals.
 */
@Service
public class FeatureSuggestionService {

    /** The features worth suggesting; custom permissions are not something task titles can show. */
    private static final Set<Feature> SUGGESTED = EnumSet.of(Feature.LABELS, Feature.PRIORITIES, Feature.DUE_DATES,
            Feature.ASSIGNEES);
    /** Enough to see a pattern; the newest tasks show how the board is used now. */
    private static final int MAX_TASKS = 500;
    private static final int MAX_MEMBERS = 5000;

    private final JdbcTemplate jdbcTemplate;
    private final ServerFeatureService serverFeatureService;
    private final PermissionEvaluationService evaluation;

    public FeatureSuggestionService(
            JdbcTemplate jdbcTemplate,
            ServerFeatureService serverFeatureService,
            PermissionEvaluationService evaluation) {
        this.jdbcTemplate = jdbcTemplate;
        this.serverFeatureService = serverFeatureService;
        this.evaluation = evaluation;
    }

    /**
     * @param scope     SERVER when the server has the feature off, BOARD when only this board does
     * @param canEnable whether the asking user may switch it on there (Manage Server permissions for the server,
     *                  editing the board's details for the board)
     */
    public record Suggestion(String feature, String signal, List<String> examples, int count, String scope,
            boolean canEnable) {
    }

    public List<Suggestion> suggestions(Long serverId, Long boardId, Long userId) {
        Set<Feature> candidates = EnumSet.copyOf(SUGGESTED);
        candidates.removeAll(serverFeatureService.enabled(serverId, boardId));
        candidates.removeAll(dismissed(boardId));
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<TaskText> tasks = jdbcTemplate.query(
                "SELECT title, description, column_id FROM tasks WHERE board_id = ? AND is_archived = false "
                        + "ORDER BY task_id DESC LIMIT " + MAX_TASKS,
                (rs, i) -> new TaskText(rs.getString("title"), rs.getString("description"), rs.getLong("column_id")),
                boardId);
        if (tasks.size() < FeatureSuggestionDetector.MIN_TASKS) {
            return List.of();
        }
        List<ColumnText> columns = jdbcTemplate.query(
                "SELECT column_id, name FROM columns WHERE board_id = ? ORDER BY position",
                (rs, i) -> new ColumnText(rs.getLong("column_id"), rs.getString("name")), boardId);
        Set<String> memberNames = candidates.contains(Feature.ASSIGNEES) || candidates.contains(Feature.LABELS)
                ? memberNames(serverId)
                : Set.of();

        Set<Feature> serverOn = serverFeatureService.enabled(serverId);
        List<Suggestion> suggestions = new ArrayList<>();
        Boolean managesServer = null;
        Boolean editsBoard = null;
        for (Finding finding : FeatureSuggestionDetector.detect(tasks, columns, memberNames)) {
            Feature feature = finding.feature();
            if (!candidates.contains(feature)) {
                continue;
            }
            boolean onlyBoard = serverOn.contains(feature);
            boolean canEnable;
            if (onlyBoard) {
                editsBoard = editsBoard != null ? editsBoard
                        : evaluation.isAllowed(serverId, boardId, userId, "EDIT_BOARD_DETAILS");
                canEnable = editsBoard;
            } else {
                managesServer = managesServer != null ? managesServer
                        : evaluation.isAllowed(serverId, null, userId, "MANAGE_SERVER_PERMISSIONS");
                canEnable = managesServer;
            }
            suggestions.add(new Suggestion(feature.name(), finding.signal(), finding.examples(), finding.count(),
                    onlyBoard ? "BOARD" : "SERVER", canEnable));
        }
        return suggestions;
    }

    /** Hides the suggestions for these features on the board, for everyone, for good. */
    public void dismiss(Long boardId, Set<Feature> features) {
        for (Feature feature : features) {
            jdbcTemplate.update("INSERT INTO board_feature_suggestion_dismissals (board_id, feature) VALUES (?, ?) "
                    + "ON CONFLICT DO NOTHING", boardId, feature.name());
        }
    }

    private Set<Feature> dismissed(Long boardId) {
        Set<Feature> dismissed = EnumSet.noneOf(Feature.class);
        for (String name : jdbcTemplate.queryForList(
                "SELECT feature FROM board_feature_suggestion_dismissals WHERE board_id = ?", String.class, boardId)) {
            try {
                dismissed.add(Feature.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // A feature this version does not know.
            }
        }
        return dismissed;
    }

    private Set<String> memberNames(Long serverId) {
        Set<String> names = new HashSet<>();
        jdbcTemplate.query("""
                SELECT u.username, u.global_name, m.nickname FROM server_members m
                JOIN users u ON u.user_id = m.user_id WHERE m.server_id = ? LIMIT %d
                """.formatted(MAX_MEMBERS), rs -> {
            FeatureSuggestionDetector.addMemberNames(
                    Arrays.asList(rs.getString("username"), rs.getString("global_name"),
                            rs.getString("nickname")), names);
        }, serverId);
        return names;
    }
}
