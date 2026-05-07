package com.poppy.domain.notification.service;

import com.poppy.common.config.redis.DistributedLockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationCleanupSchedulerTest {
    @Mock
    private NotificationCleanupService cleanupService;
    @Mock
    private DistributedLockService lockService;

    @InjectMocks
    private NotificationCleanupScheduler notificationCleanupScheduler;

    @Test
    void 락_획득_실패시_정리_서비스_호출되지_않음() {
        // given
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(false);

        // when
        notificationCleanupScheduler.cleanupOldNotifications();

        // then
        verify(cleanupService, never()).cleanupOldNotifications();
        verify(lockService, never()).unlock(anyString());
    }

    @Test
    void 락_획득_성공시_정리_서비스_호출_후_언락() {
        // given
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(cleanupService.cleanupOldNotifications()).thenReturn(5);

        // when
        notificationCleanupScheduler.cleanupOldNotifications();

        // then
        verify(cleanupService, times(1)).cleanupOldNotifications();
        verify(lockService, times(1)).unlock(anyString());
    }

    @Test
    void 정리_중_예외_발생해도_락_해제() {
        // given
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(cleanupService.cleanupOldNotifications())
                .thenThrow(new RuntimeException("테스트 예외"));

        // when
        notificationCleanupScheduler.cleanupOldNotifications();

        // then
        verify(lockService, times(1)).unlock(anyString());
    }
}