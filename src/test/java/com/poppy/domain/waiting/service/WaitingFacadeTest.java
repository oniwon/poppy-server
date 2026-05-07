package com.poppy.domain.waiting.service;

import com.poppy.common.exception.BusinessException;
import com.poppy.domain.popupStore.entity.PopupStore;
import com.poppy.domain.user.entity.Role;
import com.poppy.domain.user.entity.User;
import com.poppy.domain.waiting.dto.response.WaitingRspDto;
import com.poppy.domain.waiting.entity.Waiting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaitingFacadeTest {
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock rLock;
    @Mock
    private UserWaitingService userWaitingService;

    @InjectMocks
    private WaitingFacade waitingFacade;

    private WaitingRspDto sampleDto;

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .id(1L)
                .email("user@test.com")
                .role(Role.ROLE_USER)
                .build();

        PopupStore store = PopupStore.builder()
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

        Waiting waiting = Waiting.builder()
                .popupStore(store)
                .user(user)
                .waitingNumber(1)
                .waitingDate(LocalDate.now())
                .waitingTime(LocalTime.now())
                .build();

        sampleDto = WaitingRspDto.from(waiting);
    }

    @Test
    void 락_획득_성공_시_서비스_호출_후_언락() throws InterruptedException {
        // given
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(userWaitingService.registerWaiting(anyLong())).thenReturn(sampleDto);

        // when
        WaitingRspDto result = waitingFacade.registerWaiting(1L);

        // then
        assertNotNull(result);
        assertEquals(1, result.getWaitingNumber());
        verify(userWaitingService).registerWaiting(1L);
        verify(rLock).unlock();
    }

    @Test
    void 락_획득_실패_시_예외_발생() throws InterruptedException {
        // given
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        // when & then
        assertThrows(BusinessException.class, () -> waitingFacade.registerWaiting(1L));
    }
}