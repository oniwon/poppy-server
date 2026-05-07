package com.poppy.domain.waiting.service;

import com.poppy.common.exception.BusinessException;
import com.poppy.domain.notification.entity.NotificationType;
import com.poppy.domain.notification.service.NotificationService;
import com.poppy.domain.popupStore.entity.PopupStore;
import com.poppy.domain.popupStore.repository.PopupStoreRepository;
import com.poppy.domain.user.entity.Role;
import com.poppy.domain.user.entity.User;
import com.poppy.domain.user.repository.LoginUserProvider;
import com.poppy.domain.waiting.dto.response.UserWaitingHistoryRspDto;
import com.poppy.domain.waiting.dto.response.WaitingRspDto;
import com.poppy.domain.waiting.entity.Waiting;
import com.poppy.domain.waiting.entity.WaitingStatus;
import com.poppy.domain.waiting.repository.WaitingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserWaitingServiceTest {
    @Mock
    private WaitingRepository waitingRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private PopupStoreRepository popupStoreRepository;
    @Mock
    private WaitingUtils waitingUtils;
    @Mock
    private LoginUserProvider loginUserProvider;

    @InjectMocks
    private UserWaitingService userWaitingService;

    private User user;
    private PopupStore popupStore;
    private Waiting waiting;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(1L)
                .email("user@test.com")
                .role(Role.ROLE_USER)
                .build();

        popupStore = PopupStore.builder()
                .id(1L)
                .name("테스트 매장")
                .masterUser(user)
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(7))
                .openingTime(LocalTime.of(0, 0))
                .closingTime(LocalTime.of(23, 59))
                .isActive(true)
                .isEnd(false)
                .build();

        waiting = Waiting.builder()
                .popupStore(popupStore)
                .user(user)
                .waitingNumber(1)
                .waitingDate(LocalDate.now())
                .waitingTime(LocalTime.now())
                .build();
    }

    @Test
    void 웨이팅_등록_성공() {
        // given
        when(loginUserProvider.getLoggedInUser()).thenReturn(user);
        when(popupStoreRepository.findById(anyLong())).thenReturn(Optional.of(popupStore));
        when(waitingRepository.findMaxWaitingNumberByStoreId(anyLong())).thenReturn(Optional.of(0));
        when(waitingRepository.save(any(Waiting.class))).thenReturn(waiting);

        // when
        WaitingRspDto result = userWaitingService.registerWaiting(1L);

        // then
        assertNotNull(result);
        assertEquals(1, result.getWaitingNumber());
    }

    @Test
    void 웨이팅_등록_최대인원초과() {
        // given
        when(loginUserProvider.getLoggedInUser()).thenReturn(user);
        when(popupStoreRepository.findById(anyLong())).thenReturn(Optional.of(popupStore));
        when(waitingRepository.countByPopupStoreIdAndStatusIn(anyLong(), anySet())).thenReturn(51L);

        // when & then
        assertThrows(BusinessException.class, () ->
                userWaitingService.registerWaiting(1L));
    }

    @Test
    void 대기내역_조회_성공() {
        // given
        when(loginUserProvider.getLoggedInUser()).thenReturn(user);
        when(waitingRepository.findByUserIdOrderByWaitingDateDescWaitingTimeDesc(anyLong()))  // 메서드명 변경
                .thenReturn(List.of(waiting));

        // when
        List<UserWaitingHistoryRspDto> result = userWaitingService.getUserWaitingHistory();

        // then
        assertFalse(result.isEmpty());
        assertEquals(1, result.size());
    }

    @Test
    void 대기상세_조회_성공() {
        // given
        when(loginUserProvider.getLoggedInUser()).thenReturn(user);
        when(waitingRepository.findById(anyLong())).thenReturn(Optional.of(waiting));

        // when
        UserWaitingHistoryRspDto result = userWaitingService.getWaitingDetail(1L);

        // then
        assertNotNull(result);
        assertEquals(waiting.getWaitingNumber(), result.getWaitingNumber());
    }

    @Test
    void 대기_취소_성공() {
        // given
        when(waitingRepository.findById(anyLong())).thenReturn(Optional.of(waiting));

        // when
        userWaitingService.cancelWaiting(1L, 1L);

        // then
        assertEquals(WaitingStatus.CANCELED, waiting.getStatus());
        verify(notificationService).sendNotification(waiting, NotificationType.WAITING_CANCEL, null);
    }

    @Test
    void 대기_취소_호출상태_실패() {
        // given
        waiting.updateStatus(WaitingStatus.CALLED);
        when(waitingRepository.findById(anyLong())).thenReturn(Optional.of(waiting));

        // when & then
        assertThrows(BusinessException.class, () ->
                userWaitingService.cancelWaiting(1L, 1L));
    }

    @Test
    void 다음_대기번호_생성_성공() {
        // given
        when(waitingRepository.findMaxWaitingNumberByStoreId(anyLong())).thenReturn(Optional.of(5));

        // when
        Integer nextNumber = ReflectionTestUtils.invokeMethod(userWaitingService, "getNextWaitingNumber", 1L);

        // then
        assertEquals(6, nextNumber);
    }

    @Test
    void 첫_대기번호_생성_성공() {
        // given
        when(waitingRepository.findMaxWaitingNumberByStoreId(anyLong())).thenReturn(Optional.empty());

        // when
        Integer nextNumber = ReflectionTestUtils.invokeMethod(userWaitingService, "getNextWaitingNumber", 1L);

        // then
        assertEquals(1, nextNumber);
    }
}