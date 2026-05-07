package com.poppy.domain.reservation.service;

import com.poppy.common.exception.BusinessException;
import com.poppy.domain.payment.dto.ReservationPaymentRspDto;
import com.poppy.domain.popupStore.entity.PopupStore;
import com.poppy.domain.reservation.entity.Reservation;
import com.poppy.domain.reservation.entity.ReservationStatus;
import com.poppy.domain.user.entity.User;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationFacadeTest {
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock rLock;
    @Mock
    private ReservationService reservationService;

    @InjectMocks
    private ReservationFacade reservationFacade;

    private Long storeId;
    private LocalDate date;
    private LocalTime time;
    private int person;
    private PopupStore popupStore;
    private User user;
    private Reservation reservation;

    @BeforeEach
    void setUp() {
        storeId = 1L;
        date = LocalDate.of(2024, 12, 5);
        time = LocalTime.of(14, 0);
        person = 2;

        popupStore = PopupStore.builder().id(storeId).build();
        user = User.builder().id(1L).build();
        reservation = Reservation.builder()
                .popupStore(popupStore)
                .user(user)
                .date(date)
                .time(time)
                .status(ReservationStatus.PENDING)
                .person(person)
                .build();
    }

    @Test
    void 예약_락_획득_성공시_서비스_호출_후_언락() throws InterruptedException {
        // given
        ReservationPaymentRspDto dto = ReservationPaymentRspDto.builder()
                .orderId("test-order-id")
                .amount(10000L)
                .build();
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(reservationService.reservation(storeId, date, time, person)).thenReturn(dto);

        // when
        ReservationPaymentRspDto result = reservationFacade.reservation(storeId, date, time, person);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getOrderId()).isEqualTo("test-order-id");
        verify(reservationService, times(1)).reservation(storeId, date, time, person);
        verify(rLock, times(1)).unlock();
    }

    @Test
    void 예약_락_획득_실패시_예외_발생() throws InterruptedException {
        // given
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> reservationFacade.reservation(storeId, date, time, person))
                .isInstanceOf(BusinessException.class);

        verify(reservationService, never()).reservation(anyLong(), any(), any(), anyInt());
        verify(rLock, never()).unlock();
    }

    @Test
    void 결제_완료_락_획득_후_서비스_호출_후_언락() throws InterruptedException {
        // given
        String orderId = "test-order-id";
        when(reservationService.findReservationByOrderId(orderId)).thenReturn(reservation);
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(reservationService.completeReservation(orderId)).thenReturn(reservation);

        // when
        Reservation result = reservationFacade.completeReservation(orderId);

        // then
        assertThat(result).isNotNull();
        verify(reservationService, times(1)).completeReservation(orderId);
        verify(rLock, times(1)).unlock();
    }

    @Test
    void 예약_취소_락_획득_후_서비스_호출_후_언락() throws InterruptedException {
        // given
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        // when
        reservationFacade.cancelReservation(user.getId(), storeId, date, time, person);

        // then
        verify(reservationService, times(1)).cancelReservation(user.getId(), storeId, date, time, person);
        verify(rLock, times(1)).unlock();
    }

    @Test
    void 예약ID로_취소_락_획득_후_서비스_호출_후_언락() throws InterruptedException {
        // given
        Long reservationId = 1L;
        when(reservationService.findReservation(user.getId(), reservationId)).thenReturn(reservation);
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        // when
        reservationFacade.cancelReservationByReservationId(user.getId(), reservationId);

        // then
        verify(reservationService, times(1)).cancelReservationByReservationId(user.getId(), reservationId);
        verify(rLock, times(1)).unlock();
    }

    @Test
    void 서비스_예외_발생시에도_언락() throws InterruptedException {
        // given
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(reservationService.reservation(storeId, date, time, person))
                .thenThrow(new RuntimeException("서비스 예외"));

        // when & then
        assertThatThrownBy(() -> reservationFacade.reservation(storeId, date, time, person))
                .isInstanceOf(RuntimeException.class);

        verify(rLock, times(1)).unlock();
    }
}