package com.poppy.domain.reservation.service;

import com.poppy.common.exception.BusinessException;
import com.poppy.common.exception.ErrorCode;
import com.poppy.domain.payment.dto.ReservationPaymentRspDto;
import com.poppy.domain.reservation.entity.Reservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationFacade {
    private final RedissonClient redissonClient;
    private final ReservationService reservationService;

    private static final String LOCK_PREFIX = "reservation:lock:";
    private static final long WAIT_TIME = 5L;
    private static final long LEASE_TIME = 10L;

    public ReservationPaymentRspDto reservation(Long storeId, LocalDate date, LocalTime time, int person) {
        return executeWithLock(
                buildLockKey(storeId, date, time),
                ErrorCode.RESERVATION_CONFLICT,
                ErrorCode.RESERVATION_FAILED,
                () -> reservationService.reservation(storeId, date, time, person)
        );
    }

    public Reservation completeReservation(String orderId) {
        Reservation tempReservation = reservationService.findReservationByOrderId(orderId);
        return executeWithLock(
                buildLockKey(tempReservation.getPopupStore().getId(), tempReservation.getDate(), tempReservation.getTime()),
                ErrorCode.RESERVATION_CONFLICT,
                ErrorCode.RESERVATION_FAILED,
                () -> reservationService.completeReservation(orderId)
        );
    }

    public void cancelReservation(Long userId, Long storeId, LocalDate date, LocalTime time, int person) {
        executeWithLock(
                buildLockKey(storeId, date, time),
                ErrorCode.RESERVATION_CONFLICT,
                ErrorCode.CANCELLATION_FAILED,
                () -> {
                    reservationService.cancelReservation(userId, storeId, date, time, person);
                    return null;
                }
        );
    }

    public void cancelReservationByReservationId(Long userId, Long reservationId) {
        Reservation reservation = reservationService.findReservation(userId, reservationId);
        executeWithLock(
                buildLockKey(reservation.getPopupStore().getId(), reservation.getDate(), reservation.getTime()),
                ErrorCode.RESERVATION_CONFLICT,
                ErrorCode.CANCELLATION_FAILED,
                () -> {
                    reservationService.cancelReservationByReservationId(userId, reservationId);
                    return null;
                }
        );
    }

    private String buildLockKey(Long storeId, LocalDate date, LocalTime time) {
        return LOCK_PREFIX + storeId + ":" + date + ":" + time;
    }

    private <T> T executeWithLock(String lockKey, ErrorCode conflictError, ErrorCode interruptError, Supplier<T> action) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(WAIT_TIME, LEASE_TIME, TimeUnit.SECONDS);
            if (!acquired) throw new BusinessException(conflictError);
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(interruptError);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}