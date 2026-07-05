package com.poppy.domain.reservation.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RedisSlotService {
    // Lua 스크립트 결과 코드
    public static final long SLOT_KEY_NOT_FOUND = -2L;  // 슬롯 키 없음
    public static final long INSUFFICIENT_SLOT = -1L;   // 잔여 슬롯 부족

    private final RedisTemplate<String, Integer> redisTemplate;

    // "확인 + 차감"을 단일 Lua 스크립트로 원자 실행 → 별도의 락 없이 오버부킹 차단
    private static final RedisScript<Long> DECREMENT_IF_AVAILABLE = RedisScript.of(
            """
            local current = redis.call('GET', KEYS[1])
            if current == false then
                return -2
            end
            if tonumber(current) < tonumber(ARGV[1]) then
                return -1
            end
            return redis.call('DECRBY', KEYS[1], ARGV[1])
            """, Long.class);

    // Redis에 슬롯 정보 저장하는 공통 메서드
    public void setSlotToRedis(Long storeId, LocalDate date, LocalTime time, int availableSlot) {
        redisTemplate.opsForValue().set(slotKey(storeId, date, time), availableSlot, 24, TimeUnit.HOURS);
    }

    // Redis에서 슬롯 정보 조회
    public Integer getSlotFromRedis(Long storeId, LocalDate date, LocalTime time) {
        return redisTemplate.opsForValue().get(slotKey(storeId, date, time));
    }

    // Redis 슬롯 삭제
    public void deleteSlot(Long storeId, LocalDate date, LocalTime time) {
        redisTemplate.delete(slotKey(storeId, date, time));
    }

    // 잔여 슬롯 확인과 차감을 원자적으로 수행
    // 반환값: 차감 후 잔여 슬롯 수, SLOT_KEY_NOT_FOUND(-2), INSUFFICIENT_SLOT(-1)
    public long decrementIfAvailable(Long storeId, LocalDate date, LocalTime time, int person) {
        Long result = redisTemplate.execute(
                DECREMENT_IF_AVAILABLE,
                List.of(slotKey(storeId, date, time)),
                person
        );
        return result != null ? result : SLOT_KEY_NOT_FOUND;
    }

    // Redis의 슬롯 감소 (취소 보상 롤백용)
    public void decrementSlot(Long storeId, LocalDate date, LocalTime time, int person) {
        String slotKey = slotKey(storeId, date, time);
        Long result = redisTemplate.opsForValue().decrement(slotKey, person);

        if (result != null && result < 0) {
            // 슬롯이 음수가 되면 롤백
            redisTemplate.opsForValue().increment(slotKey, person);
            throw new IllegalStateException("Redis 슬롯이 음수가 될 수 없습니다.");
        }
    }

    // Redis의 슬롯 증가
    public void incrementSlot(Long storeId, LocalDate date, LocalTime time, int person) {
        redisTemplate.opsForValue().increment(slotKey(storeId, date, time), person);
    }

    // 슬롯 키 생성 (모든 슬롯 연산이 동일한 키를 사용하도록 단일화)
    private String slotKey(Long storeId, LocalDate date, LocalTime time) {
        return String.format("slot:%d:%s:%s", storeId, date, time);
    }
}
