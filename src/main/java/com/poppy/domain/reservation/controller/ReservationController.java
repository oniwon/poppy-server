package com.poppy.domain.reservation.controller;

import com.poppy.common.api.RspTemplate;
import com.poppy.domain.payment.dto.ReservationPaymentRspDto;
import com.poppy.domain.reservation.dto.request.ReservationReqDto;
import com.poppy.domain.reservation.service.ReservationFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/reservation")
@RequiredArgsConstructor
public class ReservationController {
    private final ReservationFacade reservationFacade;

    // 예약 요청 시 결제
    @PostMapping
    public RspTemplate<ReservationPaymentRspDto> reservation(@Valid @RequestBody ReservationReqDto reservationReqDto) {
        ReservationPaymentRspDto paymentInfo = reservationFacade.reservation(
                reservationReqDto.getPopupStoreId(),
                reservationReqDto.getDate(),
                reservationReqDto.getTime(),
                reservationReqDto.getPerson()
        );
        return new RspTemplate<>(HttpStatus.OK, "결제를 진행해주세요.", paymentInfo);
    }
}
