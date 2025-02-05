package com.poppy.domain.popupStore.entity;

import com.poppy.common.entity.BaseTimeEntity;
import com.poppy.common.entity.Images;
import com.poppy.domain.storeCategory.entity.StoreCategory;
import com.poppy.domain.wishList.entity.WishList;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "popup_stores")
@Getter
public class PopupStore {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String thumbnail;

    @Column(nullable = false)
    private String location;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    private LocalDateTime time;  // 팝업 스토어 예약 가능 시간 (ex. 17:00)

    // 현재 예약 인원은 Redis에서 처리
    @Column(name = "available_slot", nullable = false)
    private Integer availableSlot;  // 예약 가능한 총 인원

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;  // 갑자기 사람 몰릴 때 대비

    @Column(name = "is_end", nullable = false)
    private Boolean isEnd;  // 팝업 스토어가 종료되었는지 확인

    @Column(nullable = false)
    private Double rating = 0.0;  // 5점 만점 (기본 0점, 리뷰 개수에 따라 점수 변동)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private StoreCategory storeCategory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_id")
    private Images image;  // 상세 페이지에서 보여줄 이미지

    @Embedded
    private BaseTimeEntity baseTime;

    @OneToMany(mappedBy = "popupStore", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WishList> wishLists = new ArrayList<>();
}
