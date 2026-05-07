package com.poppy.domain.waiting.controller;

import com.poppy.common.api.RspTemplate;
import com.poppy.domain.waiting.dto.response.UserWaitingHistoryRspDto;
import com.poppy.domain.waiting.dto.response.WaitingRspDto;
import com.poppy.domain.waiting.service.UserWaitingService;
import com.poppy.domain.waiting.service.WaitingFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class UserWaitingController {
    private final WaitingFacade waitingFacade;
    private final UserWaitingService userWaitingService;

    @PostMapping("/waiting")
    public RspTemplate<WaitingRspDto> registerWaiting(@RequestParam Long storeId) {
        return new RspTemplate<>(
                HttpStatus.CREATED,
                "대기 등록이 완료되었습니다.",
                waitingFacade.registerWaiting(storeId)
        );
    }

    @GetMapping("/users/{id}/waiting")
    public RspTemplate<List<UserWaitingHistoryRspDto>> getWaitingHistory(@PathVariable Long id) {
        return new RspTemplate<>(
                HttpStatus.OK,
                "대기 내역 조회 성공",
                userWaitingService.getUserWaitingHistory()
        );
    }

    @GetMapping("/users/{id}/waiting/{waitingId}")
    public RspTemplate<UserWaitingHistoryRspDto> getWaitingDetail(@PathVariable Long id, @PathVariable Long waitingId) {
        return new RspTemplate<>(
                HttpStatus.OK,
                "대기 상세 조회 성공",
                userWaitingService.getWaitingDetail(waitingId)
        );
    }

    @DeleteMapping("/users/{id}/waiting/{waitingId}")
    public RspTemplate<?> cancelWaiting(
            @PathVariable Long id,
            @PathVariable Long waitingId,
            @RequestParam Long storeId) {
        userWaitingService.cancelWaiting(storeId, waitingId);
        return new RspTemplate<>(
                HttpStatus.OK,
                "대기가 취소되었습니다."
        );
    }
}