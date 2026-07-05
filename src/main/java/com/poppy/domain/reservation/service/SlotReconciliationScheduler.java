package com.poppy.domain.reservation.service;

import com.poppy.common.config.redis.DistributedLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SlotReconciliationScheduler {
    private static final String RECONCILE_SCHEDULE = "0 */10 * * * *"; // 10분마다

    private final SlotReconciliationService reconciliationService;
    private final DistributedLockService lockService;

    @Scheduled(cron = RECONCILE_SCHEDULE)
    public void reconcile() {
        if (!lockService.tryLock(DistributedLockService.RESERVATION_SLOT_RECONCILE_LOCK, 10L, 300L)) {
            log.debug("Failed to acquire slot-reconcile lock. Skipping this execution.");
            return;
        }

        try {
            int reconciled = reconciliationService.reconcileFutureSlots();
            if (reconciled > 0) log.info("Reconciled {} Redis slot counters against DB", reconciled);
        } catch (Exception e) {
            log.error("Error in slot-reconcile scheduler: {}", e.getMessage(), e);
        } finally {
            lockService.unlock(DistributedLockService.RESERVATION_SLOT_RECONCILE_LOCK);
        }
    }
}
