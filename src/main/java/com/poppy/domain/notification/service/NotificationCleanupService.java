package com.poppy.domain.notification.service;

import com.poppy.domain.notification.entity.Notification;
import com.poppy.domain.notification.repository.NotificationRepository;
import com.poppy.domain.user.entity.User;
import com.poppy.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationCleanupService {
    private static final int MAX_NOTIFICATIONS = 30;

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    @Transactional
    public int cleanupOldNotifications() {
        List<User> users = userRepository.findAll();
        int deletedCount = 0;

        for (User user : users) {
            Long userId = user.getId();
            List<Notification> notificationsToDelete =
                    notificationRepository.findNotificationsExceedingLimit(userId, MAX_NOTIFICATIONS);

            if (!notificationsToDelete.isEmpty()) {
                notificationRepository.deleteAll(notificationsToDelete);
                deletedCount += notificationsToDelete.size();
            }
        }

        return deletedCount;
    }
}
