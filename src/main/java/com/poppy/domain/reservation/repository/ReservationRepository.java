package com.poppy.domain.reservation.repository;

import com.poppy.domain.reservation.entity.Reservation;
import com.poppy.domain.reservation.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    Optional<Reservation> findByUserIdAndPopupStoreIdAndDate(Long userId, Long popupStoreId, LocalDate date);
    Optional<Reservation> findByUserIdAndPopupStoreIdAndDateAndTime(Long userId, Long popupStoreId, LocalDate date, LocalTime time);
    List<Reservation> findAllByUserId(Long userId);
    Optional<Reservation> findByIdAndUserId(Long id, Long userId);
    Optional<Reservation> findByUserIdAndPopupStoreIdAndDateAndStatus(Long userId, Long storeId, LocalDate date, ReservationStatus status);
    List<Reservation> findByDateAndTimeAndStatus(LocalDate date, LocalTime time, ReservationStatus status);
    boolean existsByPopupStoreIdAndDateIn(Long popupStoreId, Set<LocalDate> dates);

    // PENDING → CHECKED 원자 전이(CAS). 영향행 1일 때만 확정 성공
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Reservation r SET r.status = com.poppy.domain.reservation.entity.ReservationStatus.CHECKED " +
            "WHERE r.id = :id AND r.status = com.poppy.domain.reservation.entity.ReservationStatus.PENDING")
    int confirmIfPending(@Param("id") Long id);

    // PENDING → CANCELED 원자 전이(CAS). 영향행 1일 때만 슬롯 원복 수행 → 이중 원복 방지
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Reservation r SET r.status = com.poppy.domain.reservation.entity.ReservationStatus.CANCELED " +
            "WHERE r.id = :id AND r.status = com.poppy.domain.reservation.entity.ReservationStatus.PENDING")
    int cancelIfPending(@Param("id") Long id);

    // 만료된 홀드(PENDING & expiresAt 경과) 조회 — 만료 스케줄러용
    List<Reservation> findByStatusAndExpiresAtBefore(ReservationStatus status, LocalDateTime now);

    // reconcile용: 특정 슬롯의 점유 좌석 수 = CHECKED + 유효 PENDING 의 person 합
    @Query("SELECT COALESCE(SUM(r.person), 0) FROM Reservation r " +
            "WHERE r.popupStore.id = :storeId AND r.date = :date AND r.time = :time " +
            "AND (r.status = com.poppy.domain.reservation.entity.ReservationStatus.CHECKED " +
            "     OR (r.status = com.poppy.domain.reservation.entity.ReservationStatus.PENDING AND r.expiresAt > :now))")
    int sumOccupiedSeats(@Param("storeId") Long storeId,
                         @Param("date") LocalDate date,
                         @Param("time") LocalTime time,
                         @Param("now") LocalDateTime now);
}
