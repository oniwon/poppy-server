package com.poppy.domain.reservation.entity;

import com.poppy.common.entity.BaseTimeEntity;
import com.poppy.domain.popupStore.entity.PopupStore;
import com.poppy.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(
        name = "reservations",
        // 같은 유저가 같은 팝업의 같은 날짜에 예약을 동시에 2건 만드는 race 방지
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_user_store_date",
                columnNames = {"user_id", "popup_store_id", "date"})
)
@NoArgsConstructor
@Getter
public class Reservation extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate date;  // 예약한 팝업 스토어 날짜

    @Column(nullable = false)
    private LocalTime time;  // 예약한 팝업 스토어 시간

    @Column(nullable = false)
    private Integer person;  // 예약 인원

    @Enumerated(EnumType.STRING)
    private ReservationStatus status;  // 예약 상태

    // 선점(홀드) 만료 시각. PENDING 상태에서 이 시각이 지나면 만료 스케줄러가 회수
    private LocalDateTime expiresAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "popup_store_id", nullable = false)
    private PopupStore popupStore;

    @Builder
    public Reservation(PopupStore popupStore, User user, ReservationStatus status, LocalTime time, LocalDate date, Integer person, LocalDateTime expiresAt) {
        this.popupStore = popupStore;
        this.user = user;
        this.status = status;
        this.time = time;
        this.date = date;
        this.person = person;
        this.expiresAt = expiresAt;
    }

    public void updateStatus(ReservationStatus status) {
        this.status = status;
    }

    public void updateReservation(LocalTime time, int person) {
        this.time = time;
        this.person = person;
    }

    public void updateExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
