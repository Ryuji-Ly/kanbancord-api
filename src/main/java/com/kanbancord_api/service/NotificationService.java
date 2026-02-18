package com.kanbancord_api.service;

import com.kanbancord_api.model.Notification;
import com.kanbancord_api.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    public Notification create(Notification notification) {
        return notificationRepository.save(notification);
    }

    @Transactional(readOnly = true)
    public Optional<Notification> findById(Long id) {
        return notificationRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Notification> findAll() {
        return notificationRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Notification> findByUserId(Long userId) {
        return notificationRepository.findByUser_UserId(userId);
    }

    @Transactional(readOnly = true)
    public List<Notification> findByUserIdOrdered(Long userId) {
        return notificationRepository.findByUser_UserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<Notification> findByUserIdAndReadStatus(Long userId, Boolean isRead) {
        return notificationRepository.findByUser_UserIdAndIsRead(userId, isRead);
    }

    @Transactional(readOnly = true)
    public Long countUnreadByUserId(Long userId) {
        return notificationRepository.countByUser_UserIdAndIsRead(userId, false);
    }

    public Notification update(Notification notification) {
        return notificationRepository.save(notification);
    }

    public void markAsRead(Long id) {
        Optional<Notification> notification = notificationRepository.findById(id);
        notification.ifPresent(n -> {
            n.setIsRead(true);
            notificationRepository.save(n);
        });
    }

    public void markAllAsReadForUser(Long userId) {
        List<Notification> unreadNotifications = notificationRepository.findByUser_UserIdAndIsRead(userId, false);
        unreadNotifications.forEach(n -> n.setIsRead(true));
        notificationRepository.saveAll(unreadNotifications);
    }

    public void deleteById(Long id) {
        notificationRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return notificationRepository.existsById(id);
    }
}
