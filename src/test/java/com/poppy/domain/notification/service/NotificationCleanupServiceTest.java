package com.poppy.domain.notification.service;

import com.poppy.domain.notification.entity.Notification;
import com.poppy.domain.notification.entity.NotificationType;
import com.poppy.domain.notification.repository.NotificationRepository;
import com.poppy.domain.user.entity.User;
import com.poppy.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationCleanupServiceTest {
    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private NotificationCleanupService cleanupService;

    private User user1;
    private User user2;
    private List<Notification> notificationsToDelete;

    @BeforeEach
    void setUp() {
        user1 = User.builder()
                .id(1L)
                .email("test1@example.com")
                .build();

        user2 = User.builder()
                .id(2L)
                .email("test2@example.com")
                .build();

        notificationsToDelete = List.of(
                Notification.builder()
                        .message("테스트 알림 1")
                        .type(NotificationType.NOTICE)
                        .user(user1)
                        .build(),
                Notification.builder()
                        .message("테스트 알림 2")
                        .type(NotificationType.NOTICE)
                        .user(user1)
                        .build()
        );

        ReflectionTestUtils.setField(notificationsToDelete.get(0), "id", 1L);
        ReflectionTestUtils.setField(notificationsToDelete.get(1), "id", 2L);
    }

    @Test
    void 삭제할_알림이_있는_경우_정상_삭제() {
        // given
        when(userRepository.findAll()).thenReturn(List.of(user1));
        when(notificationRepository.findNotificationsExceedingLimit(eq(user1.getId()), anyInt()))
                .thenReturn(notificationsToDelete);

        // when
        int deletedCount = cleanupService.cleanupOldNotifications();

        // then
        verify(notificationRepository, times(1)).deleteAll(notificationsToDelete);
        assertEquals(2, deletedCount);
    }

    @Test
    void 삭제할_알림이_없는_경우_삭제_수행하지_않음() {
        // given
        when(userRepository.findAll()).thenReturn(List.of(user1));
        when(notificationRepository.findNotificationsExceedingLimit(eq(user1.getId()), anyInt()))
                .thenReturn(List.of());

        // when
        int deletedCount = cleanupService.cleanupOldNotifications();

        // then
        verify(notificationRepository, never()).deleteAll(any());
        assertEquals(0, deletedCount);
    }

    @Test
    void 여러_유저의_알림_삭제_처리() {
        // given
        when(userRepository.findAll()).thenReturn(List.of(user1, user2));
        when(notificationRepository.findNotificationsExceedingLimit(eq(user1.getId()), anyInt()))
                .thenReturn(notificationsToDelete);
        when(notificationRepository.findNotificationsExceedingLimit(eq(user2.getId()), anyInt()))
                .thenReturn(List.of());

        // when
        int deletedCount = cleanupService.cleanupOldNotifications();

        // then
        verify(notificationRepository, times(1)).deleteAll(notificationsToDelete);
        verify(notificationRepository, times(2)).findNotificationsExceedingLimit(anyLong(), anyInt());
        assertEquals(2, deletedCount);
    }
}