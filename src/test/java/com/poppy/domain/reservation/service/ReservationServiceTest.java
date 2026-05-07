package com.poppy.domain.reservation.service;

import com.poppy.common.exception.BusinessException;
import com.poppy.common.exception.ErrorCode;
import com.poppy.domain.notification.service.NotificationService;
import com.poppy.domain.payment.entity.Payment;
import com.poppy.domain.payment.entity.PaymentStatus;
import com.poppy.domain.payment.repository.PaymentRepository;
import com.poppy.domain.payment.service.PaymentService;
import com.poppy.domain.popupStore.entity.PopupStore;
import com.poppy.domain.popupStore.entity.ReservationType;
import com.poppy.domain.popupStore.repository.PopupStoreRepository;
import com.poppy.domain.reservation.entity.PopupStoreStatus;
import com.poppy.domain.reservation.entity.Reservation;
import com.poppy.domain.reservation.entity.ReservationAvailableSlot;
import com.poppy.domain.reservation.entity.ReservationStatus;
import com.poppy.domain.reservation.repository.ReservationAvailableSlotRepository;
import com.poppy.domain.reservation.repository.ReservationRepository;
import com.poppy.domain.user.entity.User;
import com.poppy.domain.user.repository.LoginUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {
    @Mock
    private PopupStoreRepository popupStoreRepository;
    @Mock
    private ReservationAvailableSlotRepository reservationAvailableSlotRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private RedisSlotService redisSlotService;
    @Mock
    private AsyncRedisSlotDecrementService asyncRedisSlotDecrementService;
    @Mock
    private PaymentService paymentService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private LoginUserProvider loginUserProvider;

    @InjectMocks
    private ReservationService reservationService;

    private Long storeId;
    private LocalDate date;
    private LocalTime time;
    private int person;
    private PopupStore popupStore;
    private User user;
    private ReservationAvailableSlot slot;

    @BeforeEach
    void setUp() {
        storeId = 1L;
        date = LocalDate.of(2024, 12, 5);
        time = LocalTime.of(14, 0);
        person = 2;

        popupStore = PopupStore.builder()
                .id(storeId)
                .reservationType(ReservationType.ONLINE)
                .price(5000L)
                .build();

        user = User.builder()
                .id(1L)
                .build();

        slot = ReservationAvailableSlot.builder()
                .popupStore(popupStore)
                .date(date)
                .time(time)
                .availableSlot(28)
                .totalSlot(28)
                .status(PopupStoreStatus.AVAILABLE)
                .build();
    }

    @Test
    void 예약_저장_실패시_예외_전파() {
        // given
        when(redisSlotService.getSlotFromRedis(anyLong(), any(), any())).thenReturn(28);
        when(popupStoreRepository.findById(storeId)).thenReturn(Optional.of(popupStore));
        when(loginUserProvider.getLoggedInUser()).thenReturn(user);
        when(reservationRepository.findByUserIdAndPopupStoreIdAndDate(anyLong(), anyLong(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(reservationRepository.save(any(Reservation.class)))
                .thenThrow(new BusinessException(ErrorCode.RESERVATION_FAILED));

        // when & then
        assertThatThrownBy(() -> reservationService.reservation(storeId, date, time, person))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.RESERVATION_FAILED.getMessage());

        verify(reservationRepository, times(1)).save(any());
        verify(paymentRepository, never()).save(any());
        verify(redisSlotService, never()).decrementSlot(any(), any(), any(), anyInt());
    }

    @Test
    void 결제_완료시_예약_확정() {
        // given
        String orderId = UUID.randomUUID().toString();

        Reservation pendingReservation = Reservation.builder()
                .popupStore(popupStore)
                .user(user)
                .date(date)
                .time(time)
                .status(ReservationStatus.PENDING)
                .person(person)
                .build();

        Payment mockPayment = Payment.builder()
                .orderId(orderId)
                .paymentKey("test-payment-key")
                .status(PaymentStatus.PENDING)
                .amount(10000L)
                .reservation(pendingReservation)
                .user(user)
                .build();

        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(mockPayment));
        when(popupStoreRepository.findById(storeId)).thenReturn(Optional.of(popupStore));
        when(reservationAvailableSlotRepository.findByPopupStoreIdAndDateAndTime(storeId, date, time))
                .thenReturn(Optional.of(slot));
        when(reservationRepository.findByUserIdAndPopupStoreIdAndDateAndStatus(
                eq(user.getId()), eq(storeId), eq(date), eq(ReservationStatus.CHECKED)))
                .thenReturn(Optional.empty());
        when(reservationRepository.save(any(Reservation.class))).thenReturn(pendingReservation);
        doNothing().when(redisSlotService).decrementSlot(anyLong(), any(), any(), anyInt());

        // when
        Reservation confirmedReservation = reservationService.completeReservation(orderId);

        // then
        assertThat(confirmedReservation.getStatus()).isEqualTo(ReservationStatus.CHECKED);
        verify(redisSlotService, times(1)).decrementSlot(eq(storeId), eq(date), eq(time), eq(person));
    }

    @Test
    void 예약_취소시_결제_취소() {
        // given
        Reservation reservation = Reservation.builder()
                .popupStore(popupStore)
                .user(user)
                .date(date)
                .time(time)
                .status(ReservationStatus.CHECKED)
                .person(person)
                .build();

        Payment payment = Payment.builder()
                .orderId("test-order-id")
                .paymentKey("test-payment-key")
                .status(PaymentStatus.DONE)
                .amount(10000L)
                .reservation(reservation)
                .user(user)
                .build();

        when(redisSlotService.getSlotFromRedis(anyLong(), any(), any())).thenReturn(20);
        when(reservationRepository.findByUserIdAndPopupStoreIdAndDateAndTime(
                user.getId(), popupStore.getId(), date, time))
                .thenReturn(Optional.of(reservation));
        when(paymentRepository.findByReservationId(reservation.getId()))
                .thenReturn(Optional.of(payment));
        when(reservationAvailableSlotRepository.findByPopupStoreIdAndDateAndTime(storeId, date, time))
                .thenReturn(Optional.of(slot));

        doAnswer(inv -> {
            payment.updateStatus(PaymentStatus.CANCELED);
            return null;
        }).when(paymentService).cancelPayment(eq("test-order-id"), anyString());

        // when
        reservationService.cancelReservation(user.getId(), popupStore.getId(), date, time, person);

        // then
        verify(paymentService).cancelPayment(eq("test-order-id"), anyString());
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
    }
}