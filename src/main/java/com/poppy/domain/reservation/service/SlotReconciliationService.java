package com.poppy.domain.reservation.service;

import com.poppy.domain.reservation.entity.ReservationAvailableSlot;
import com.poppy.domain.reservation.repository.ReservationAvailableSlotRepository;
import com.poppy.domain.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Redis 재고 카운터를 DB(진실 원천)로부터 재계산해 드리프트/고아 홀드를 복구한다.
 * 공식: Redis[slot] = totalSlot − (CHECKED + 유효 PENDING 의 person 합)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlotReconciliationService {
    private final ReservationAvailableSlotRepository slotRepository;
    private final ReservationRepository reservationRepository;
    private final RedisSlotService redisSlotService;

    @Transactional(readOnly = true)
    public int reconcileFutureSlots() {
        LocalDateTime now = LocalDateTime.now();
        List<ReservationAvailableSlot> slots = slotRepository.findByDateGreaterThanEqual(LocalDate.now());

        int reconciled = 0;
        for (ReservationAvailableSlot slot : slots) {
            Long storeId = slot.getPopupStore().getId();
            int occupied = reservationRepository.sumOccupiedSeats(storeId, slot.getDate(), slot.getTime(), now);
            int available = Math.max(0, slot.getTotalSlot() - occupied);

            Integer current = redisSlotService.getSlotFromRedis(storeId, slot.getDate(), slot.getTime());
            if (current == null || current != available) {
                redisSlotService.setSlotToRedis(storeId, slot.getDate(), slot.getTime(), available);
                reconciled++;
            }
        }
        return reconciled;
    }
}
