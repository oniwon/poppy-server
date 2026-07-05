package com.poppy.domain.reservation.service;

import com.poppy.common.config.redis.DistributedLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class HoldExpiryScheduler {
    private static final long CHECK_INTERVAL = 60_000; // 1분

    private final ReservationService reservationService;
    private final DistributedLockService lockService;

    // 결제 없이 방치된 홀드(PENDING)를 회수해 좌석을 되돌린다.
    // 락(밖) → 트랜잭션(안) 순서: 락은 스케줄러가, 실제 작업은 @Transactional 서비스가 담당.
    @Scheduled(fixedDelay = CHECK_INTERVAL)
    public void expireStaleHolds() {
        if (!lockService.tryLock(DistributedLockService.RESERVATION_HOLD_EXPIRY_LOCK)) {
            log.debug("Failed to acquire hold-expiry lock. Skipping this execution.");
            return;
        }

        try {
            int released = reservationService.expireHolds();
            if (released > 0) log.info("Expired {} stale reservation holds", released);
        } catch (Exception e) {
            log.error("Error in hold-expiry scheduler: {}", e.getMessage(), e);
        } finally {
            lockService.unlock(DistributedLockService.RESERVATION_HOLD_EXPIRY_LOCK);
        }
    }
}
