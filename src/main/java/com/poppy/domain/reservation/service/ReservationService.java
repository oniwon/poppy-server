package com.poppy.domain.reservation.service;

import com.poppy.common.exception.BusinessException;
import com.poppy.common.exception.ErrorCode;
import com.poppy.domain.notification.entity.NotificationType;
import com.poppy.domain.notification.service.NotificationService;
import com.poppy.domain.payment.entity.Payment;
import com.poppy.domain.payment.entity.PaymentStatus;
import com.poppy.domain.payment.repository.PaymentRepository;
import com.poppy.domain.payment.service.PaymentService;
import com.poppy.domain.popupStore.entity.PopupStore;
import com.poppy.domain.popupStore.entity.ReservationType;
import com.poppy.domain.popupStore.repository.PopupStoreRepository;
import com.poppy.domain.payment.dto.ReservationPaymentRspDto;
import com.poppy.domain.reservation.entity.PopupStoreStatus;
import com.poppy.domain.reservation.entity.Reservation;
import com.poppy.domain.reservation.entity.ReservationStatus;
import com.poppy.domain.reservation.repository.ReservationAvailableSlotRepository;
import com.poppy.domain.reservation.repository.ReservationRepository;
import com.poppy.domain.user.dto.response.UserReservationDetailRspDto;
import com.poppy.domain.user.dto.response.UserReservationRspDto;
import com.poppy.domain.user.entity.User;
import com.poppy.domain.user.repository.LoginUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {
    private final PopupStoreRepository popupStoreRepository;
    private final ReservationAvailableSlotRepository reservationAvailableSlotRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final RedisSlotService redisSlotService;
    private final PaymentService paymentService;
    private final NotificationService notificationService;
    private final LoginUserProvider loginUserProvider;  // 로그인 유저 확인용

    // 결제 완료 전까지 좌석을 붙잡아 두는 홀드 유효 시간 (PG 결제 타임아웃보다 넉넉히)
    private static final Duration HOLD_WINDOW = Duration.ofMinutes(10);

    // 어플에서 진행하는 예약 — 진입 시 Redis Lua로 좌석을 원자적으로 선점(홀드)
    @Transactional
    public ReservationPaymentRspDto reservation(Long storeId, LocalDate date, LocalTime time, int person) {
        // 파라미터 예외 처리
        if (storeId == null || date == null || time == null || person <= 0)
            throw new BusinessException(ErrorCode.NOT_NULL_PARAMETER);

        // 팝업 스토어 조회 및 유형 판단
        PopupStore popupStore = popupStoreRepository.findById(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));

        if (popupStore.getReservationType() != ReservationType.ONLINE)
            throw new BusinessException(ErrorCode.INVALID_RESERVATION);

        // 로그인 유저 확인
        User user = loginUserProvider.getLoggedInUser();

        // 기존 예약 체크 — 확정/진행/방문 상태가 있으면 재예약 불가, CANCELED만 재사용
        Optional<Reservation> existing =
                reservationRepository.findByUserIdAndPopupStoreIdAndDate(user.getId(), storeId, date);
        if (existing.isPresent() && existing.get().getStatus() != ReservationStatus.CANCELED)
            throw new BusinessException(ErrorCode.ALREADY_BOOKED);

        // Redis Lua 원자 차감으로 좌석 선점(홀드) — 락 없이 오버부킹 차단, 탈락은 여기서 즉시 종료(DB 미접근)
        long remaining = redisSlotService.decrementIfAvailable(storeId, date, time, person);
        if (remaining == RedisSlotService.SLOT_KEY_NOT_FOUND) throw new BusinessException(ErrorCode.SLOT_NOT_FOUND);
        if (remaining == RedisSlotService.INSUFFICIENT_SLOT) throw new BusinessException(ErrorCode.NO_AVAILABLE_SLOT);

        try {
            LocalDateTime expiresAt = LocalDateTime.now().plus(HOLD_WINDOW);

            Reservation reservation;
            if (existing.isPresent()) {
                // 취소됐던 예약 재사용
                reservation = existing.get();
                paymentRepository.deleteByReservationId(reservation.getId());   // 기존 결제 정보 삭제
                reservation.updateReservation(time, person);
                reservation.updateStatus(ReservationStatus.PENDING);
                reservation.updateExpiresAt(expiresAt);
            } else {
                reservation = reservationRepository.save(Reservation.builder()
                        .popupStore(popupStore)
                        .user(new User(user.getId()))
                        .date(date)
                        .time(time)
                        .status(ReservationStatus.PENDING)
                        .person(person)
                        .expiresAt(expiresAt)
                        .build());
            }

            return createPaymentAndGetResponse(reservation, user, person);
        }
        catch (Exception e) {
            // 홀드 이후 DB 작업 실패 시 Redis 좌석 원복(보상)
            redisSlotService.incrementSlot(storeId, date, time, person);
            throw e;
        }
    }

    // 결제 정보 생성 및 응답 DTO 반환 메서드
    private ReservationPaymentRspDto createPaymentAndGetResponse(Reservation reservation, User user, int person) {
        String orderId = UUID.randomUUID().toString();
        Long amount = reservation.getPopupStore().getPrice() * person;

        Payment payment = Payment.builder()
                .orderId(orderId)
                .amount(amount)
                .status(PaymentStatus.PENDING)
                .user(user)
                .reservation(reservation)
                .build();
        paymentRepository.save(payment);

        return ReservationPaymentRspDto.builder()
                .orderId(orderId)
                .amount(amount)
                .storeName(reservation.getPopupStore().getName())
                .date(reservation.getDate())
                .time(reservation.getTime())
                .person(person)
                .build();
    }

    // 결제 성공 → 예약 확정. 홀드는 결제 전에 이미 잡혀 있으므로 Redis는 건드리지 않고 상태만 CAS 전이
    @Transactional
    public Reservation completeReservation(String orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        Reservation reservation = payment.getReservation();
        Long reservationId = reservation.getId();
        Long storeId = reservation.getPopupStore().getId();
        LocalDate date = reservation.getDate();
        LocalTime time = reservation.getTime();
        int person = reservation.getPerson();

        // PENDING → CHECKED 원자 전이(CAS)
        int confirmed = reservationRepository.confirmIfPending(reservationId);
        if (confirmed == 0) {
            Reservation fresh = reservationRepository.findById(reservationId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

            // 콜백 재시도 등으로 이미 확정된 경우 → 멱등 처리
            if (fresh.getStatus() == ReservationStatus.CHECKED) return fresh;

            // 홀드가 만료 회수된 뒤 뒤늦게 결제 성공한 레이스 → 좌석 재확보 시도
            long remaining = redisSlotService.decrementIfAvailable(storeId, date, time, person);
            if (remaining == RedisSlotService.SLOT_KEY_NOT_FOUND || remaining == RedisSlotService.INSUFFICIENT_SLOT) {
                log.error("결제 성공했으나 만료 후 좌석 재확보 실패(환불 필요): orderId={}", orderId);
                throw new BusinessException(ErrorCode.NO_AVAILABLE_SLOT);
            }
            fresh.updateStatus(ReservationStatus.CHECKED);
            reservationRepository.save(fresh);
        }

        // 확정 점유를 DB availableSlot 미러에 반영 (Redis는 홀드 시 이미 차감됨)
        applyConfirmedOccupancy(storeId, date, time, person);

        Reservation confirmedReservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        notificationService.sendNotification(confirmedReservation, confirmedReservation.getStatus(), NotificationType.RESERVATION_CHECK);
        return confirmedReservation;
    }

    // 확정 시 DB availableSlot 미러 차감 + 매진 상태 갱신 (표시/리포팅용, 진실 원천은 아님)
    private void applyConfirmedOccupancy(Long storeId, LocalDate date, LocalTime time, int person) {
        int updated = reservationAvailableSlotRepository.decreaseSlotIfAvailable(storeId, date, time, person);
        if (updated == 0) {
            log.warn("availableSlot 미러 차감 실패(드리프트 가능): storeId={}, date={}, time={}", storeId, date, time);
            return;
        }
        reservationAvailableSlotRepository.findByPopupStoreIdAndDateAndTime(storeId, date, time)
                .filter(s -> s.getAvailableSlot() == 0)
                .ifPresent(s -> s.updatePopupStatus(PopupStoreStatus.FULL));
    }

    // 결제 실패/이탈 시 홀드 반납 — PENDING → CANCELED 전이가 성공한 1회에만 Redis 원복
    @Transactional
    public void releaseHold(String orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);
        if (payment == null || payment.getReservation() == null) return;

        Reservation reservation = payment.getReservation();
        Long reservationId = reservation.getId();
        Long storeId = reservation.getPopupStore().getId();
        LocalDate date = reservation.getDate();
        LocalTime time = reservation.getTime();
        int person = reservation.getPerson();

        int canceled = reservationRepository.cancelIfPending(reservationId);
        if (canceled == 1) {
            redisSlotService.incrementSlot(storeId, date, time, person);   // 정확히 1회 원복
        }
    }

    // 만료된 홀드(PENDING & expiresAt 경과) 일괄 회수 — 만료 스케줄러에서 호출
    @Transactional
    public int expireHolds() {
        List<Reservation> expired = reservationRepository
                .findByStatusAndExpiresAtBefore(ReservationStatus.PENDING, LocalDateTime.now());

        // CAS UPDATE(clearAutomatically)가 영속성 컨텍스트를 비우므로 필요한 값을 먼저 스냅샷
        record Hold(Long id, Long storeId, LocalDate date, LocalTime time, int person) {}
        List<Hold> holds = expired.stream()
                .map(r -> new Hold(r.getId(), r.getPopupStore().getId(), r.getDate(), r.getTime(), r.getPerson()))
                .collect(Collectors.toList());

        int released = 0;
        for (Hold h : holds) {
            if (reservationRepository.cancelIfPending(h.id()) == 1) {
                redisSlotService.incrementSlot(h.storeId(), h.date(), h.time(), h.person());   // 정확히 1회 원복
                released++;
            }
        }
        return released;
    }

    // 확정된 예약 취소 (결제 취소 + 좌석 원복)
    @Transactional
    public void cancelReservation(Long userId, Long storeId, LocalDate date, LocalTime time, int person) {
        if (storeId == null || date == null || time == null || person <= 0)
            throw new BusinessException(ErrorCode.NOT_NULL_PARAMETER);

        Reservation reservation = reservationRepository.findByUserIdAndPopupStoreIdAndDateAndTime(userId, storeId, date, time)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        if (reservation.getStatus() != ReservationStatus.CHECKED)
            throw new BusinessException(ErrorCode.INVALID_RESERVATION);

        // 결제 정보 조회 및 결제 취소
        Payment payment = paymentRepository.findByReservationId(reservation.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        paymentService.cancelPayment(payment.getOrderId(), "고객 예약 취소");

        // 예약 상태 변경
        reservation.updateStatus(ReservationStatus.CANCELED);

        // DB availableSlot 미러 복원 + 매진 해제
        reservationAvailableSlotRepository.increaseSlot(storeId, date, time, person);
        reservationAvailableSlotRepository.findByPopupStoreIdAndDateAndTime(storeId, date, time)
                .filter(s -> s.isAvailable() && s.getStatus() == PopupStoreStatus.FULL)
                .ifPresent(s -> s.updatePopupStatus(PopupStoreStatus.AVAILABLE));

        // Redis 원복은 DB 반영이 끝난 뒤 마지막에 (DB 실패 시 Redis 오증가 방지)
        redisSlotService.incrementSlot(storeId, date, time, person);
    }

    // 유저 별 예약 취소
    @Transactional
    public void cancelReservationByReservationId(Long userId, Long reservationId) {
        Reservation reservation = reservationRepository.findByIdAndUserId(reservationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        cancelReservation(
                userId,
                reservation.getPopupStore().getId(),
                reservation.getDate(),
                reservation.getTime(),
                reservation.getPerson()
        );
        notificationService.sendNotification(reservation, ReservationStatus.CANCELED, NotificationType.RESERVATION_CANCEL); // 알림 전송
    }

    // 유저의 모든 예약 조회
    @Transactional(readOnly = true)
    public List<UserReservationRspDto> getReservations(Long userId) {
        List<Reservation> reservations = reservationRepository.findAllByUserId(userId);
        return reservations.stream()
                .map(UserReservationRspDto::from)
                .collect(Collectors.toList());
    }

    // 유저의 특정 예약 상세 조회
    @Transactional(readOnly = true)
    public UserReservationDetailRspDto getReservationById(Long userId, Long reservationId) {
        Reservation reservation = reservationRepository.findByIdAndUserId(reservationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        return UserReservationDetailRspDto.from(reservation);
    }
}
