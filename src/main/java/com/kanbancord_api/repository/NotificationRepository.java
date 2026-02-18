package com.kanbancord_api.repository;

import com.kanbancord_api.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByUser_UserId(Long userId);

    List<Notification> findByUser_UserIdAndIsRead(Long userId, Boolean isRead);

    List<Notification> findByUser_UserIdOrderByCreatedAtDesc(Long userId);

    Long countByUser_UserIdAndIsRead(Long userId, Boolean isRead);
}
