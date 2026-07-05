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
import com.poppy.domain.payment.dto.ReservationPaymentRspDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {
    @Mock private PopupStoreRepository popupStoreRepository;
    @Mock private ReservationAvailableSlotRepository reservationAvailableSlotRepository;
    @Mock private ReservationRepository reservationRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private RedisSlotService redisSlotService;
    @Mock private PaymentService paymentService;
    @Mock private NotificationService notificationService;
    @Mock private LoginUserProvider loginUserProvider;

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
        date = LocalDate.of(2026, 8, 1);
        time = LocalTime.of(14, 0);
        person = 2;

        popupStore = PopupStore.builder()
                .id(storeId)
                .reservationType(ReservationType.ONLINE)
                .price(5000L)
                .build();

        user = User.builder().id(1L).build();

        slot = ReservationAvailableSlot.builder()
                .popupStore(popupStore)
                .date(date)
                .time(time)
                .availableSlot(26)
                .totalSlot(28)
                .status(PopupStoreStatus.AVAILABLE)
                .build();
    }

    private Reservation reservationWithId(Long id, ReservationStatus status) {
        Reservation r = Reservation.builder()
                .popupStore(popupStore).user(user).date(date).time(time)
                .status(status).person(person).expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    // ── 예약(선점) ──────────────────────────────────────────────

    @Test
    void 예약시_Lua로_좌석_선점하고_PENDING_생성() {
        // given
        when(popupStoreRepository.findById(storeId)).thenReturn(Optional.of(popupStore));
        when(loginUserProvider.getLoggedInUser()).thenReturn(user);
        when(reservationRepository.findByUserIdAndPopupStoreIdAndDate(user.getId(), storeId, date))
                .thenReturn(Optional.empty());
        when(redisSlotService.decrementIfAvailable(storeId, date, time, person)).thenReturn(24L);
        when(reservationRepository.save(any(Reservation.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // when
        ReservationPaymentRspDto result = reservationService.reservation(storeId, date, time, person);

        // then
        assertThat(result.getAmount()).isEqualTo(5000L * person);
        verify(redisSlotService, times(1)).decrementIfAvailable(storeId, date, time, person);
        verify(reservationRepository, times(1)).save(argThat(r -> r.getStatus() == ReservationStatus.PENDING && r.getExpiresAt() != null));
        verify(paymentRepository, times(1)).save(any(Payment.class));
        verify(redisSlotService, never()).incrementSlot(anyLong(), any(), any(), anyInt());
    }

    @Test
    void 매진시_홀드_실패하고_DB_미접근() {
        // given
        when(popupStoreRepository.findById(storeId)).thenReturn(Optional.of(popupStore));
        when(loginUserProvider.getLoggedInUser()).thenReturn(user);
        when(reservationRepository.findByUserIdAndPopupStoreIdAndDate(user.getId(), storeId, date))
                .thenReturn(Optional.empty());
        when(redisSlotService.decrementIfAvailable(storeId, date, time, person))
                .thenReturn(RedisSlotService.INSUFFICIENT_SLOT);

        // when & then
        assertThatThrownBy(() -> reservationService.reservation(storeId, date, time, person))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.NO_AVAILABLE_SLOT.getMessage());

        verify(reservationRepository, never()).save(any());
        verify(paymentRepository, never()).save(any());
        verify(redisSlotService, never()).incrementSlot(anyLong(), any(), any(), anyInt());
    }

    @Test
    void 홀드_후_DB실패시_Redis_원복() {
        // given
        when(popupStoreRepository.findById(storeId)).thenReturn(Optional.of(popupStore));
        when(loginUserProvider.getLoggedInUser()).thenReturn(user);
        when(reservationRepository.findByUserIdAndPopupStoreIdAndDate(user.getId(), storeId, date))
                .thenReturn(Optional.empty());
        when(redisSlotService.decrementIfAvailable(storeId, date, time, person)).thenReturn(24L);
        when(reservationRepository.save(any(Reservation.class)))
                .thenThrow(new BusinessException(ErrorCode.RESERVATION_FAILED));

        // when & then
        assertThatThrownBy(() -> reservationService.reservation(storeId, date, time, person))
                .isInstanceOf(BusinessException.class);

        verify(redisSlotService, times(1)).incrementSlot(storeId, date, time, person);   // 보상 원복
    }

    @Test
    void 이미_확정된_예약이_있으면_예약_거절() {
        // given
        when(popupStoreRepository.findById(storeId)).thenReturn(Optional.of(popupStore));
        when(loginUserProvider.getLoggedInUser()).thenReturn(user);
        when(reservationRepository.findByUserIdAndPopupStoreIdAndDate(user.getId(), storeId, date))
                .thenReturn(Optional.of(reservationWithId(1L, ReservationStatus.CHECKED)));

        // when & then
        assertThatThrownBy(() -> reservationService.reservation(storeId, date, time, person))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.ALREADY_BOOKED.getMessage());

        verify(redisSlotService, never()).decrementIfAvailable(anyLong(), any(), any(), anyInt());
    }

    // ── 결제 완료(확정) ─────────────────────────────────────────

    @Test
    void 결제완료시_CAS로_확정하고_Redis는_미변경() {
        // given
        Reservation pending = reservationWithId(1L, ReservationStatus.PENDING);
        Payment payment = Payment.builder()
                .orderId("order-1").status(PaymentStatus.PENDING).amount(10000L)
                .reservation(pending).user(user).build();

        when(paymentRepository.findByOrderId("order-1")).thenReturn(Optional.of(payment));
        when(reservationRepository.confirmIfPending(1L)).thenReturn(1);
        when(reservationAvailableSlotRepository.decreaseSlotIfAvailable(storeId, date, time, person)).thenReturn(1);
        when(reservationAvailableSlotRepository.findByPopupStoreIdAndDateAndTime(storeId, date, time))
                .thenReturn(Optional.of(slot));
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(reservationWithId(1L, ReservationStatus.CHECKED)));

        // when
        Reservation result = reservationService.completeReservation("order-1");

        // then
        assertThat(result.getStatus()).isEqualTo(ReservationStatus.CHECKED);
        verify(reservationRepository, times(1)).confirmIfPending(1L);
        verify(redisSlotService, never()).decrementIfAvailable(anyLong(), any(), any(), anyInt());
        verify(redisSlotService, never()).incrementSlot(anyLong(), any(), any(), anyInt());
    }

    @Test
    void 결제완료_콜백_재시도시_멱등처리() {
        // given — 이미 CHECKED라 CAS가 0행
        Reservation checked = reservationWithId(1L, ReservationStatus.CHECKED);
        Payment payment = Payment.builder()
                .orderId("order-1").status(PaymentStatus.DONE).amount(10000L)
                .reservation(checked).user(user).build();

        when(paymentRepository.findByOrderId("order-1")).thenReturn(Optional.of(payment));
        when(reservationRepository.confirmIfPending(1L)).thenReturn(0);
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(checked));

        // when
        Reservation result = reservationService.completeReservation("order-1");

        // then
        assertThat(result.getStatus()).isEqualTo(ReservationStatus.CHECKED);
        verify(reservationAvailableSlotRepository, never()).decreaseSlotIfAvailable(anyLong(), any(), any(), anyInt());
        verify(redisSlotService, never()).decrementIfAvailable(anyLong(), any(), any(), anyInt());
    }

    // ── 홀드 반납/만료 ──────────────────────────────────────────

    @Test
    void 결제실패시_홀드_반납은_CAS성공_1회만_원복() {
        // given
        Reservation pending = reservationWithId(1L, ReservationStatus.PENDING);
        Payment payment = Payment.builder()
                .orderId("order-1").status(PaymentStatus.PENDING).amount(10000L)
                .reservation(pending).user(user).build();

        when(paymentRepository.findByOrderId("order-1")).thenReturn(Optional.of(payment));
        when(reservationRepository.cancelIfPending(1L)).thenReturn(1);

        // when
        reservationService.releaseHold("order-1");

        // then
        verify(redisSlotService, times(1)).incrementSlot(storeId, date, time, person);
    }

    @Test
    void 이미_취소된_홀드는_이중_원복하지_않음() {
        // given — cancelIfPending이 0행(이미 만료 스케줄러가 처리)
        Reservation pending = reservationWithId(1L, ReservationStatus.PENDING);
        Payment payment = Payment.builder()
                .orderId("order-1").status(PaymentStatus.PENDING).amount(10000L)
                .reservation(pending).user(user).build();

        when(paymentRepository.findByOrderId("order-1")).thenReturn(Optional.of(payment));
        when(reservationRepository.cancelIfPending(1L)).thenReturn(0);

        // when
        reservationService.releaseHold("order-1");

        // then
        verify(redisSlotService, never()).incrementSlot(anyLong(), any(), any(), anyInt());
    }

    @Test
    void 만료된_홀드_일괄_회수시_성공한_건만_원복() {
        // given
        Reservation h1 = reservationWithId(1L, ReservationStatus.PENDING);
        Reservation h2 = reservationWithId(2L, ReservationStatus.PENDING);
        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(ReservationStatus.PENDING), any()))
                .thenReturn(List.of(h1, h2));
        when(reservationRepository.cancelIfPending(1L)).thenReturn(1);
        when(reservationRepository.cancelIfPending(2L)).thenReturn(0);   // 경합으로 이미 확정/취소됨

        // when
        int released = reservationService.expireHolds();

        // then
        assertThat(released).isEqualTo(1);
        verify(redisSlotService, times(1)).incrementSlot(storeId, date, time, person);
    }

    // ── 확정 예약 취소 ──────────────────────────────────────────

    @Test
    void 확정예약_취소시_결제취소_및_좌석_원복() {
        // given
        Reservation checked = reservationWithId(1L, ReservationStatus.CHECKED);
        Payment payment = Payment.builder()
                .orderId("order-1").status(PaymentStatus.DONE).amount(10000L)
                .reservation(checked).user(user).build();

        when(reservationRepository.findByUserIdAndPopupStoreIdAndDateAndTime(user.getId(), storeId, date, time))
                .thenReturn(Optional.of(checked));
        when(paymentRepository.findByReservationId(1L)).thenReturn(Optional.of(payment));
        when(reservationAvailableSlotRepository.findByPopupStoreIdAndDateAndTime(storeId, date, time))
                .thenReturn(Optional.of(slot));

        // when
        reservationService.cancelReservation(user.getId(), storeId, date, time, person);

        // then
        verify(paymentService).cancelPayment(eq("order-1"), anyString());
        verify(reservationAvailableSlotRepository, times(1)).increaseSlot(storeId, date, time, person);
        verify(redisSlotService, times(1)).incrementSlot(storeId, date, time, person);
        assertThat(checked.getStatus()).isEqualTo(ReservationStatus.CANCELED);
    }
}
