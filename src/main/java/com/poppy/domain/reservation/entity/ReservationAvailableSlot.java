package com.poppy.domain.reservation.entity;

import com.poppy.domain.popupStore.entity.PopupStore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "reservation_available_slots")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReservationAvailableSlot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "popup_store_id", nullable = false)
    private PopupStore popupStore;

    @Column(nullable = false)
    private LocalDate date; // 예약 가능한 날짜 (예: 2024-01-10)

    @Column(nullable = false)
    private LocalTime time; // 예약 가능한 시간대 (예: 11:00, 12:00 등)

    @Column(nullable = false)
    private int availableSlot; // 남은 슬롯 수

    @Column(nullable = false)
    private int totalSlot; // 시간대별 전체 슬롯 수

    // 휴무, 예약 마감, 예약 가능, 지난 날짜
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PopupStoreStatus status = PopupStoreStatus.AVAILABLE;   // 기본값은 예약 가능

    public boolean isAvailable() {
        return this.availableSlot > 0;
    }

    public void updatePopupStatus(PopupStoreStatus status) {
        this.status = status;
    }
}
