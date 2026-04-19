package com.kanbancord_api.controller;

import com.kanbancord_api.dto.NotificationResponse;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Notification;
import com.kanbancord_api.service.AccessValidator;
import com.kanbancord_api.service.NotificationService;
import com.kanbancord_api.service.ResourceValidator;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users/{userId}/notifications")
@Validated
public class NotificationController {

    private final NotificationService notificationService;
    private final AccessValidator accessValidator;
    private final ResourceValidator resourceValidator;

    public NotificationController(
            NotificationService notificationService,
            AccessValidator accessValidator,
            ResourceValidator resourceValidator) {
        this.notificationService = notificationService;
        this.accessValidator = accessValidator;
        this.resourceValidator = resourceValidator;
    }

    @GetMapping
    public ResponseEntity<List<NotificationResponse>> getNotificationsByUserId(
            @PathVariable Long userId,
            @RequestParam(required = false) Boolean isRead,
            @RequestParam Long requestingUserId) {

        accessValidator.requireSelf(requestingUserId, userId);

        List<Notification> notifications;
        if (isRead != null) {
            notifications = notificationService.findByUserIdAndReadStatus(userId, isRead);
        } else {
            notifications = notificationService.findByUserIdOrdered(userId);
        }

        List<NotificationResponse> responses = notifications.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{notificationId}")
    public ResponseEntity<NotificationResponse> getNotificationById(
            @PathVariable Long userId,
            @PathVariable Long notificationId,
            @RequestParam Long requestingUserId) {

        accessValidator.requireSelf(requestingUserId, userId);

        Notification notification = notificationService.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", "notificationId", notificationId));

        resourceValidator.validateNotificationBelongsToUser(notification, userId);

        return ResponseEntity.ok(mapToResponse(notification));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Long> getUnreadCount(
            @PathVariable Long userId,
            @RequestParam Long requestingUserId) {

        accessValidator.requireSelf(requestingUserId, userId);

        return ResponseEntity.ok(notificationService.countUnreadByUserId(userId));
    }

    @PatchMapping("/{notificationId}/mark-read")
    public ResponseEntity<Void> markAsRead(
            @PathVariable Long userId,
            @PathVariable Long notificationId,
            @RequestParam Long requestingUserId) {

        accessValidator.requireSelf(requestingUserId, userId);

        Notification notification = notificationService.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", "notificationId", notificationId));

        resourceValidator.validateNotificationBelongsToUser(notification, userId);

        notificationService.markAsRead(notificationId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/mark-all-read")
    public ResponseEntity<Void> markAllAsReadForUser(
            @PathVariable Long userId,
            @RequestParam Long requestingUserId) {

        accessValidator.requireSelf(requestingUserId, userId);

        notificationService.markAllAsReadForUser(userId);
        return ResponseEntity.noContent().build();
    }

    private NotificationResponse mapToResponse(Notification notification) {
        NotificationResponse response = new NotificationResponse();
        response.setNotificationId(notification.getNotificationId());
        response.setUserId(notification.getUser().getUserId());
        response.setType(notification.getType());
        response.setEntityType(notification.getEntityType());
        response.setEntityId(notification.getEntityId());
        response.setMessage(notification.getMessage());
        response.setIsRead(notification.getIsRead());
        response.setMetadata(notification.getMetadata());
        response.setCreatedAt(notification.getCreatedAt());
        return response;
    }
}
