package com.poppy.domain.notification.service;

import com.poppy.common.config.redis.DistributedLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationCleanupScheduler {
    private static final String CLEANUP_SCHEDULE = "0 0 0 * * *"; // 매일 자정에 실행

    private final NotificationCleanupService cleanupService;
    private final DistributedLockService lockService;

    @Scheduled(cron = CLEANUP_SCHEDULE)
    public void cleanupOldNotifications() {
        // 10초 동안 락 획득 시도, 성공하면 5분 동안 락 유지
        if (!lockService.tryLock(DistributedLockService.NOTIFICATION_CLEANUP_LOCK, 10L, 300L)) {
            log.debug("Failed to acquire notification cleanup lock. Skipping this execution.");
            return;
        }

        try {
            int deletedCount = cleanupService.cleanupOldNotifications();
            log.info("Old notifications cleanup completed. Deleted {} notifications", deletedCount);
        } catch (Exception e) {
            log.error("Failed to cleanup old notifications: {}", e.getMessage(), e);
        } finally {
            lockService.unlock(DistributedLockService.NOTIFICATION_CLEANUP_LOCK);
        }
    }
}