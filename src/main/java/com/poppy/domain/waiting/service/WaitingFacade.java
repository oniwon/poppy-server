package com.poppy.domain.waiting.service;

import com.poppy.common.exception.BusinessException;
import com.poppy.common.exception.ErrorCode;
import com.poppy.domain.waiting.dto.response.WaitingRspDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaitingFacade {
    private final RedissonClient redissonClient;
    private final UserWaitingService userWaitingService;

    private static final String LOCK_PREFIX = "waiting:lock:";
    public static final long WAIT_TIME = 5L;
    public static final long LEASE_TIME = 10L;

    public WaitingRspDto registerWaiting(Long storeId) {
        String lockKey = LOCK_PREFIX + storeId;
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(WAIT_TIME, LEASE_TIME, TimeUnit.SECONDS);
            if (!acquired) throw new BusinessException(ErrorCode.WAITING_CONFLICT);
            return userWaitingService.registerWaiting(storeId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.WAITING_FAILED);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
