package com.kanbancord_api.controller;

import com.kanbancord_api.dto.NotificationResponse;
import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.ResourceNotFoundException;
import com.kanbancord_api.model.Notification;
import com.kanbancord_api.service.NotificationService;
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

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<List<NotificationResponse>> getNotificationsByUserId(
            @PathVariable Long userId,
            @RequestParam(required = false) Boolean isRead,
            @RequestParam(required = false) Long requestingUserId) {

        // Authorization check: requestingUserId must match userId
        if (requestingUserId == null || !requestingUserId.equals(userId)) {
            throw new AccessDeniedException("You can only access your own notifications");
        }

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
            @RequestParam(required = false) Long requestingUserId) {

        // Authorization check: requestingUserId must match userId
        if (requestingUserId == null || !requestingUserId.equals(userId)) {
            throw new AccessDeniedException("You can only access your own notifications");
        }

        Notification notification = notificationService.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", "notificationId", notificationId));

        // Verify notification belongs to user
        if (!notification.getUser().getUserId().equals(userId)) {
            throw new AccessDeniedException("This notification does not belong to you");
        }

        return ResponseEntity.ok(mapToResponse(notification));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Long> getUnreadCount(
            @PathVariable Long userId,
            @RequestParam(required = false) Long requestingUserId) {

        // Authorization check: requestingUserId must match userId
        if (requestingUserId == null || !requestingUserId.equals(userId)) {
            throw new AccessDeniedException("You can only access your own notifications");
        }

        return ResponseEntity.ok(notificationService.countUnreadByUserId(userId));
    }

    @PatchMapping("/{notificationId}/mark-read")
    public ResponseEntity<Void> markAsRead(
            @PathVariable Long userId,
            @PathVariable Long notificationId,
            @RequestParam(required = false) Long requestingUserId) {

        // Authorization check: requestingUserId must match userId
        if (requestingUserId == null || !requestingUserId.equals(userId)) {
            throw new AccessDeniedException("You can only modify your own notifications");
        }

        Notification notification = notificationService.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", "notificationId", notificationId));

        // Verify notification belongs to user
        if (!notification.getUser().getUserId().equals(userId)) {
            throw new AccessDeniedException("This notification does not belong to you");
        }

        notificationService.markAsRead(notificationId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/mark-all-read")
    public ResponseEntity<Void> markAllAsReadForUser(
            @PathVariable Long userId,
            @RequestParam(required = false) Long requestingUserId) {

        // Authorization check: requestingUserId must match userId
        if (requestingUserId == null || !requestingUserId.equals(userId)) {
            throw new AccessDeniedException("You can only modify your own notifications");
        }

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
