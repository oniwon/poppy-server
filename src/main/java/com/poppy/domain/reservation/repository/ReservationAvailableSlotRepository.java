package com.poppy.domain.reservation.repository;

import com.poppy.domain.reservation.entity.PopupStoreStatus;
import com.poppy.domain.reservation.entity.ReservationAvailableSlot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

@Repository
public interface ReservationAvailableSlotRepository
        extends JpaRepository<ReservationAvailableSlot, Long>, ReservationAvailableSlotCustomRepository {

    @Modifying
    @Query("DELETE FROM ReservationAvailableSlot r WHERE r.popupStore.id = :popupStoreId AND r.status = :status")
    void deleteByPopupStoreIdAndStatus(Long popupStoreId, PopupStoreStatus status);

    // 슬롯 차감/증가 시 비관적 락으로 동시 쓰기 직렬화
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ReservationAvailableSlot s " +
            "WHERE s.popupStore.id = :storeId AND s.date = :date AND s.time = :time")
    Optional<ReservationAvailableSlot> findByPopupStoreIdAndDateAndTimeForUpdate(
            @Param("storeId") Long storeId,
            @Param("date") LocalDate date,
            @Param("time") LocalTime time);
}