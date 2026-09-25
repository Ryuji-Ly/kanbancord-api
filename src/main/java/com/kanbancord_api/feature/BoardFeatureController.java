package com.kanbancord_api.feature;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.event.DomainEvent;
import com.kanbancord_api.event.EventType;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A board's own simple mode: the features it keeps switched off although the server has them on.
 * The board's switches are stored even while the server has a feature off, so switching it back on
 * for the server leaves the board as it was set.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/features")
public class BoardFeatureController {

    private final ServerFeatureService serverFeatureService;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    private final ApplicationEventPublisher events;

    public BoardFeatureController(
            ServerFeatureService serverFeatureService,
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            ApplicationEventPublisher events) {
        this.serverFeatureService = serverFeatureService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.events = events;
    }

    /** The board's own switch for each feature it can switch off, e.g. {@code {"LABELS": true, ...}}. */
    @GetMapping
    public ResponseEntity<Map<String, Boolean>> get(
            @PathVariable Long serverId, @PathVariable Long boardId, @CurrentUser Long userId) {
        resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(userId, serverId, boardId, "VIEW_BOARD");
        return ResponseEntity.ok(switches(serverFeatureService.disabledOnBoard(boardId)));
    }

    /**
     * Switches features on or off for the board. Features left out keep their setting. Switching on
     * only undoes the board's own switch: a feature the server has off stays off.
     */
    @PutMapping
    @Transactional
    public ResponseEntity<Map<String, Boolean>> update(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @CurrentUser Long userId,
            @RequestBody Map<String, Boolean> changes) {
        resourceValidator.requireBoardInServer(boardId, serverId);
        authorizer.requireBoardPermission(userId, serverId, boardId, "EDIT_BOARD_DETAILS");

        Set<Feature> disabled = EnumSet.noneOf(Feature.class);
        disabled.addAll(serverFeatureService.disabledOnBoard(boardId));
        Map<String, Boolean> before = switches(disabled);
        changes.forEach((name, on) -> {
            Feature feature = parse(name);
            if (Boolean.FALSE.equals(on)) {
                disabled.add(feature);
            } else {
                disabled.remove(feature);
            }
        });

        serverFeatureService.setDisabledOnBoard(boardId, disabled);
        Map<String, Boolean> after = switches(disabled);
        if (!after.equals(before)) {
            events.publishEvent(new ServerFeatureService.BoardFeaturesChanged(boardId));
            events.publishEvent(DomainEvent.changed(EventType.BOARD_FEATURES_UPDATED, serverId, boardId, boardId,
                    userId, before, after));
        }
        return ResponseEntity.ok(after);
    }

    private static Feature parse(String name) {
        Feature feature;
        try {
            feature = Feature.valueOf(name);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown feature: " + name);
        }
        if (!feature.boardScoped()) {
            throw new BadRequestException(feature.label() + " can only be switched for the whole server");
        }
        return feature;
    }

    private static Map<String, Boolean> switches(Set<Feature> disabled) {
        Map<String, Boolean> switches = new LinkedHashMap<>();
        for (Feature feature : Feature.values()) {
            if (feature.boardScoped()) {
                switches.put(feature.name(), !disabled.contains(feature));
            }
        }
        return switches;
    }
}
