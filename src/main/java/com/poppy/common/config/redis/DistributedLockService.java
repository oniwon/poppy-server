package com.poppy.common.config.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class DistributedLockService {
    public static final String WAITING_SCHEDULER_LOCK = "waiting-scheduler-lock";
    public static final String NOTIFICATION_CLEANUP_LOCK = "notification-cleanup-lock";
    public static final String RESERVATION_24H_BEFORE_LOCK = "reservation-24h-before-lock";
    public static final String SCRAP_STORE_OPENING_LOCK = "scrap-store-opening-lock";
    public static final String RESERVATION_HOLD_EXPIRY_LOCK = "reservation-hold-expiry-lock";
    public static final String RESERVATION_SLOT_RECONCILE_LOCK = "reservation-slot-reconcile-lock";


    private final RedissonClient redissonClient;
    private static final long DEFAULT_WAIT_TIME = 5L; // 락 획득 시도 대기 시간
    private static final long DEFAULT_LEASE_TIME = 60L; // 락 점유 시간

    public boolean tryLock(String lockName) {
        return tryLock(lockName, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME);
    }

    public boolean tryLock(String lockName, long waitTime, long leaseTime) {
        RLock lock = redissonClient.getLock(lockName);
        try {
            return lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Failed to acquire lock: {}", e.getMessage());
            return false;
        }
    }

    public void unlock(String lockName) {
        RLock lock = redissonClient.getLock(lockName);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}