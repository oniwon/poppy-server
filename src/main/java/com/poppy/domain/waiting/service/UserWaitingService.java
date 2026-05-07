package com.poppy.domain.waiting.service;

import com.poppy.common.exception.BusinessException;
import com.poppy.common.exception.ErrorCode;
import com.poppy.domain.notification.entity.NotificationType;
import com.poppy.domain.notification.service.NotificationService;
import com.poppy.domain.popupStore.entity.PopupStore;
import com.poppy.domain.popupStore.repository.PopupStoreRepository;
import com.poppy.domain.user.entity.User;
import com.poppy.domain.user.repository.LoginUserProvider;
import com.poppy.domain.waiting.dto.response.UserWaitingHistoryRspDto;
import com.poppy.domain.waiting.dto.response.WaitingRspDto;
import com.poppy.domain.waiting.entity.Waiting;
import com.poppy.domain.waiting.entity.WaitingStatus;
import com.poppy.domain.waiting.repository.WaitingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserWaitingService {
    private final WaitingRepository waitingRepository;
    private final NotificationService notificationService;
    private final PopupStoreRepository popupStoreRepository;
    private final WaitingUtils waitingUtils;
    private final LoginUserProvider loginUserProvider;

    public static final String LOCK_PREFIX = "waiting:lock:";
    public static final long WAIT_TIME = 3L;
    public static final long LEASE_TIME = 3L;
    private static final int MAX_WAITING_COUNT = 50;  // 최대 대기 인원

    // 선착순 대기 등록 (앱으로 사용자가 수행)
    @Transactional
    public WaitingRspDto registerWaiting(Long storeId) {
        PopupStore store = popupStoreRepository.findById(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));

        // 운영 시간 체크
        validateOperatingHours(store);

        User user = loginUserProvider.getLoggedInUser();

        checkMaxWaitingCount(storeId);
        checkDuplicateWaiting(storeId, user.getId());

        // 대기번호 생성
        Integer waitingNumber = getNextWaitingNumber(storeId);

        Waiting waiting = waitingRepository.save(Waiting.builder()
                .popupStore(store)
                .user(user)
                .waitingNumber(waitingNumber)
                .waitingDate(LocalDate.now())
                .waitingTime(LocalTime.now())
                .build());

        // 내 앞에 몇 팀 있는지 계산
        int peopleAhead = waitingRepository.countPeopleAhead(
                storeId,
                waiting.getWaitingNumber(),
                Set.of(WaitingStatus.WAITING, WaitingStatus.CALLED)
        );

        notificationService.sendNotification(waiting, NotificationType.TEAMS_AHEAD, peopleAhead);

        return WaitingRspDto.from(waiting);
    }

    // 웨이팅 내역 조회
    @Transactional(readOnly = true)
    public List<UserWaitingHistoryRspDto> getUserWaitingHistory() {
        User user = loginUserProvider.getLoggedInUser();
        return waitingRepository.findByUserIdOrderByWaitingDateDescWaitingTimeDesc(user.getId())
                .stream()
                .map(UserWaitingHistoryRspDto::from)
                .collect(Collectors.toList());
    }

    // 웨이팅 상세 조회
    @Transactional(readOnly = true)
    public UserWaitingHistoryRspDto getWaitingDetail(Long waitingId) {
        User user = loginUserProvider.getLoggedInUser();

        Waiting waiting = waitingRepository.findById(waitingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAITING_NOT_FOUND));

        // 본인의 웨이팅만 조회 가능하도록 체크
        if (!waiting.getUser().getId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED_WAITING_ACCESS);
        }

        return UserWaitingHistoryRspDto.from(waiting);
    }

    // 대기 취소
    @Transactional
    public void cancelWaiting(Long storeId, Long waitingId) {
        // 대기 정보 조회
        Waiting waiting = waitingRepository.findById(waitingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAITING_NOT_FOUND));

        // 매장 권한 체크
        if (!waiting.getPopupStore().getId().equals(storeId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED_WAITING_ACCESS);
        }

        // 호출된 상태의 대기는 취소 불가
        if (waiting.getStatus() == WaitingStatus.CALLED) {
            throw new BusinessException(ErrorCode.CANNOT_CANCEL_CALLED_WAITING);
        }

        // 상태 변경
        waiting.updateStatus(WaitingStatus.CANCELED);

        notificationService.sendNotification(waiting, NotificationType.WAITING_CANCEL, null);
        waitingUtils.updateWaitingQueue(waiting.getPopupStore().getId(), waitingId);
    }

    // 최대 대기 인원 초과 여부 체크
    public void checkMaxWaitingCount(Long storeId) {
        long currentWaiting = waitingRepository.countByPopupStoreIdAndStatusIn(
                storeId,
                Set.of(WaitingStatus.WAITING, WaitingStatus.CALLED)
        );

        if (currentWaiting > MAX_WAITING_COUNT) {
            throw new BusinessException(ErrorCode.MAX_WAITING_EXCEEDED);
        }
    }

    // 중복 대기 체크
    private void checkDuplicateWaiting(Long storeId, Long userId) {
        boolean exists = waitingRepository.existsByPopupStoreIdAndUserIdAndStatusIn(
                storeId, userId, Set.of(WaitingStatus.WAITING, WaitingStatus.CALLED)
        );
        if (exists) {
            throw new BusinessException(ErrorCode.DUPLICATE_WAITING);
        }
    }

    // 다음 대기번호 생성
    private Integer getNextWaitingNumber(Long storeId) {
        return waitingRepository.findMaxWaitingNumberByStoreId(storeId)
                .map(num -> num + 1)
                .orElse(1);
    }

    private void validateOperatingHours(PopupStore store) {
        LocalDate currentDate = LocalDate.now();
        LocalTime currentTime = LocalTime.now().truncatedTo(ChronoUnit.MINUTES);
        LocalTime openingTime = store.getOpeningTime().truncatedTo(ChronoUnit.MINUTES);
        LocalTime closingTime = store.getClosingTime().truncatedTo(ChronoUnit.MINUTES);

        log.info("Comparing times (minutes only) - Current: {}, Opening: {}, Closing: {}",
                currentTime, openingTime, closingTime);

        // 운영 날짜 체크
        if (currentDate.isBefore(store.getStartDate()) || currentDate.isAfter(store.getEndDate())) {
            throw new BusinessException(ErrorCode.STORE_NOT_OPERATING);
        }

        // 운영 시간 체크
        if (currentTime.isBefore(openingTime) || currentTime.isAfter(closingTime)) {
            throw new BusinessException(ErrorCode.STORE_NOT_OPERATING_HOURS);
        }

        if (store.getIsEnd()) {
            throw new BusinessException(ErrorCode.STORE_ENDED);
        }

        if (!store.getIsActive()) {
            throw new BusinessException(ErrorCode.STORE_INACTIVE);
        }
    }
}
