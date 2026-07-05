package com.poppy.domain.reservation.repository;

import com.poppy.domain.reservation.entity.PopupStoreStatus;
import com.poppy.domain.reservation.entity.ReservationAvailableSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Repository
public interface ReservationAvailableSlotRepository
        extends JpaRepository<ReservationAvailableSlot, Long>, ReservationAvailableSlotCustomRepository {

    @Modifying
    @Query("DELETE FROM ReservationAvailableSlot r WHERE r.popupStore.id = :popupStoreId AND r.status = :status")
    void deleteByPopupStoreIdAndStatus(Long popupStoreId, PopupStoreStatus status);

    // reconcile 대상: 오늘 이후의 모든 슬롯
    List<ReservationAvailableSlot> findByDateGreaterThanEqual(LocalDate date);

    // 잔여 슬롯이 충분할 때만 차감하는 조건부 원자 UPDATE (락 없이 lost update 방지)
    // 반환값 0이면 잔여 슬롯 부족
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReservationAvailableSlot s SET s.availableSlot = s.availableSlot - :count " +
            "WHERE s.popupStore.id = :storeId AND s.date = :date AND s.time = :time " +
            "AND s.availableSlot >= :count")
    int decreaseSlotIfAvailable(
            @Param("storeId") Long storeId,
            @Param("date") LocalDate date,
            @Param("time") LocalTime time,
            @Param("count") int count);

    // 취소 시 슬롯 복원 원자 UPDATE
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReservationAvailableSlot s SET s.availableSlot = s.availableSlot + :count " +
            "WHERE s.popupStore.id = :storeId AND s.date = :date AND s.time = :time")
    int increaseSlot(
            @Param("storeId") Long storeId,
            @Param("date") LocalDate date,
            @Param("time") LocalTime time,
            @Param("count") int count);
}
