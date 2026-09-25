package com.kanbancord_api.user;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanbancord_api.event.UserEvent;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The signed-in user's preferences: theme, accessibility and simple view. The API stores them as
 * given; the web app decides what they mean. Changes reach the user's other tabs and devices.
 */
@RestController
@RequestMapping("/api/me/preferences")
public class PreferencesController {

    /** Plenty for a theme with every colour customised, small enough that nobody stores files here. */
    static final int MAX_BYTES = 32 * 1024;

    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    public PreferencesController(UserService userService, ObjectMapper objectMapper, ApplicationEventPublisher events) {
        this.userService = userService;
        this.objectMapper = objectMapper;
        this.events = events;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@CurrentUser Long userId) {
        return ResponseEntity.ok(current(requireUser(userId)));
    }

    /**
     * Merges the given top-level entries into the preferences, so a client can save one part (such
     * as the theme) without sending the rest. A null value removes the entry.
     */
    @PatchMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> update(@CurrentUser Long userId, @RequestBody Map<String, Object> changes) {
        User user = requireUser(userId);
        Map<String, Object> merged = current(user);
        changes.forEach((key, value) -> {
            if (value == null) {
                merged.remove(key);
            } else {
                merged.put(key, value);
            }
        });
        if (size(merged) > MAX_BYTES) {
            throw new BadRequestException("Preferences are limited to " + MAX_BYTES / 1024 + " KB");
        }

        user.setPreferences(merged);
        userService.update(user);
        events.publishEvent(new UserEvent(UserEvent.Type.PROFILE_UPDATED, userId, Map.of("preferences", merged)));
        return ResponseEntity.ok(merged);
    }

    private User requireUser(Long userId) {
        return userService.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));
    }

    private static Map<String, Object> current(User user) {
        return user.getPreferences() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(user.getPreferences());
    }

    private int size(Map<String, Object> preferences) {
        try {
            return objectMapper.writeValueAsString(preferences).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException ex) {
            throw new BadRequestException("Preferences must be plain JSON");
        }
    }
}
