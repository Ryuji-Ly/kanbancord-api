package com.kanbancord_api.feature;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.board.Board;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Features the board seems to be doing by hand, to suggest switching on. Shown to everyone who can see the board;
 * switching a feature on goes through the usual feature endpoints, which check who may.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/feature-suggestions")
public class FeatureSuggestionController {

    private final FeatureSuggestionService suggestionService;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;

    public FeatureSuggestionController(
            FeatureSuggestionService suggestionService,
            Authorizer authorizer,
            ResourceValidator resourceValidator) {
        this.suggestionService = suggestionService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
    }

    public record Suggestions(List<FeatureSuggestionService.Suggestion> suggestions) {
    }

    public record Dismissal(List<String> features) {
    }

    @GetMapping
    public ResponseEntity<Suggestions> get(
            @PathVariable Long serverId, @PathVariable Long boardId, @CurrentUser Long userId) {
        Board board = resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_BOARD");
        // An archived board is read-only: nothing to switch on there.
        if (Boolean.TRUE.equals(board.getIsArchived())) {
            return ResponseEntity.ok(new Suggestions(List.of()));
        }
        return ResponseEntity.ok(new Suggestions(suggestionService.suggestions(serverId, boardId, userId)));
    }

    /** Anyone who sees a suggestion may dismiss it; it is a hint, and it stays dismissed for the whole board. */
    @PostMapping("/dismiss")
    public ResponseEntity<Void> dismiss(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @CurrentUser Long userId,
            @RequestBody Dismissal request) {
        resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_BOARD");
        if (request.features() == null || request.features().isEmpty()) {
            throw new BadRequestException("Say which suggestions to dismiss");
        }
        Set<Feature> features = EnumSet.noneOf(Feature.class);
        for (String name : request.features()) {
            try {
                features.add(Feature.valueOf(name));
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("Unknown feature: " + name);
            }
        }
        suggestionService.dismiss(boardId, features);
        return ResponseEntity.noContent().build();
    }
}
