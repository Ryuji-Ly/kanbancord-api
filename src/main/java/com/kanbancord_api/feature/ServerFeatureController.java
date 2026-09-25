package com.kanbancord_api.feature;

import com.kanbancord_api.access.Authorizer;
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
 * The server's optional features. Anyone who can see the server can read them, since the web app
 * hides what is switched off; changing them is server administration.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/features")
public class ServerFeatureController {

    private final ServerFeatureService serverFeatureService;
    private final Authorizer authorizer;
    private final ApplicationEventPublisher events;

    public ServerFeatureController(
            ServerFeatureService serverFeatureService,
            Authorizer authorizer,
            ApplicationEventPublisher events) {
        this.serverFeatureService = serverFeatureService;
        this.authorizer = authorizer;
        this.events = events;
    }

    /** Every feature and whether it is on, e.g. {@code {"LABELS": true, "COMMENTS": false, ...}}. */
    @GetMapping
    public ResponseEntity<Map<String, Boolean>> get(@PathVariable Long serverId, @CurrentUser Long userId) {
        authorizer.requireServerPermission(userId, serverId, "VIEW_SERVER");
        return ResponseEntity.ok(asJson(serverFeatureService.all(serverId)));
    }

    /**
     * Switches features on or off. Features left out of the request keep their setting, so
     * {@code {"LABELS": true}} switches on labels only.
     */
    @PutMapping
    @Transactional
    public ResponseEntity<Map<String, Boolean>> update(
            @PathVariable Long serverId,
            @CurrentUser Long userId,
            @RequestBody Map<String, Boolean> changes) {
        authorizer.requireServerPermission(userId, serverId, "MANAGE_SERVER_PERMISSIONS");

        Map<String, Boolean> before = asJson(serverFeatureService.all(serverId));
        Set<Feature> enabled = EnumSet.noneOf(Feature.class);
        enabled.addAll(serverFeatureService.enabled(serverId));
        changes.forEach((name, on) -> {
            Feature feature = parse(name);
            if (Boolean.TRUE.equals(on)) {
                enabled.add(feature);
            } else {
                enabled.remove(feature);
            }
        });

        serverFeatureService.set(serverId, enabled);
        Map<String, Boolean> after = new LinkedHashMap<>();
        for (Feature feature : Feature.values()) {
            after.put(feature.name(), enabled.contains(feature));
        }
        if (!after.equals(before)) {
            events.publishEvent(new ServerFeatureService.FeaturesChanged(serverId));
            events.publishEvent(DomainEvent.changed(EventType.SERVER_FEATURES_UPDATED, serverId, null, serverId,
                    userId, before, after));
        }
        return ResponseEntity.ok(after);
    }

    private static Feature parse(String name) {
        try {
            return Feature.valueOf(name);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown feature: " + name);
        }
    }

    private static Map<String, Boolean> asJson(Map<Feature, Boolean> features) {
        Map<String, Boolean> json = new LinkedHashMap<>();
        features.forEach((feature, on) -> json.put(feature.name(), on));
        return json;
    }
}
