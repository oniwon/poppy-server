package com.poppy.domain.waiting.service;

import com.poppy.common.config.redis.DistributedLockService;
import com.poppy.domain.waiting.entity.Waiting;
import com.poppy.domain.waiting.entity.WaitingStatus;
import com.poppy.domain.waiting.repository.WaitingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class WaitingTimeoutScheduler {
    private static final long CHECK_INTERVAL = 60000; // 1분

    private final WaitingRepository waitingRepository;
    private final MasterWaitingService masterWaitingService;
    private final DistributedLockService lockService;

    @Scheduled(fixedDelay = CHECK_INTERVAL)
    public void checkWaitingTimeout() {
        if (!lockService.tryLock(DistributedLockService.WAITING_SCHEDULER_LOCK)) {
            log.debug("Failed to acquire waiting scheduler lock. Skipping this execution.");
            return;
        }

        try {
            List<Waiting> waitingList = waitingRepository.findByStatus(WaitingStatus.CALLED);
            LocalDateTime now = LocalDateTime.now();

            for (Waiting waiting : waitingList) {
                LocalDateTime calledTime = waiting.getUpdateTime();
                // 호출된 시간으로부터 5분이 지났는지 확인
                if (calledTime.plusMinutes(MasterWaitingService.WAITING_TIMEOUT_MINUTES).isBefore(now)) {
                    masterWaitingService.handleWaitingTimeout(waiting.getId());
                }
            }
        } catch (Exception e) {
            log.error("Error in waiting timeout scheduler: {}", e.getMessage(), e);
        } finally {
            lockService.unlock(DistributedLockService.WAITING_SCHEDULER_LOCK);
        }
    }
}