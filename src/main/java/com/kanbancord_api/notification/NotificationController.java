package com.kanbancord_api.notification;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.security.CurrentUser;
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
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;

    public NotificationController(
            NotificationService notificationService,
            Authorizer authorizer,
            ResourceValidator resourceValidator) {
        this.notificationService = notificationService;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
    }

    @GetMapping
    public ResponseEntity<List<NotificationResponse>> getNotificationsByUserId(
            @PathVariable Long userId,
            @RequestParam(required = false) Boolean isRead,
            @CurrentUser Long requestingUserId) {

        authorizer.requireSelf(requestingUserId, userId);

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
            @CurrentUser Long requestingUserId) {

        authorizer.requireSelf(requestingUserId, userId);

        Notification notification = notificationService.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", "notificationId", notificationId));

        resourceValidator.validateNotificationBelongsToUser(notification, userId);

        return ResponseEntity.ok(mapToResponse(notification));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Long> getUnreadCount(
            @PathVariable Long userId,
            @CurrentUser Long requestingUserId) {

        authorizer.requireSelf(requestingUserId, userId);

        return ResponseEntity.ok(notificationService.countUnreadByUserId(userId));
    }

    @PatchMapping("/{notificationId}/mark-read")
    public ResponseEntity<Void> markAsRead(
            @PathVariable Long userId,
            @PathVariable Long notificationId,
            @CurrentUser Long requestingUserId) {

        authorizer.requireSelf(requestingUserId, userId);

        Notification notification = notificationService.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", "notificationId", notificationId));

        resourceValidator.validateNotificationBelongsToUser(notification, userId);

        notificationService.markAsRead(notificationId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/mark-all-read")
    public ResponseEntity<Void> markAllAsReadForUser(
            @PathVariable Long userId,
            @CurrentUser Long requestingUserId) {

        authorizer.requireSelf(requestingUserId, userId);

        notificationService.markAllAsReadForUser(userId);
        return ResponseEntity.noContent().build();
    }

    private NotificationResponse mapToResponse(Notification notification) {
        return new NotificationResponse(
                notification.getNotificationId(),
                notification.getUser().getUserId(),
                notification.getType(),
                notification.getEntityType(),
                notification.getEntityId(),
                notification.getMessage(),
                notification.getIsRead(),
                notification.getMetadata(),
                notification.getCreatedAt());
    }
}
