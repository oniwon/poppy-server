package com.poppy.domain.payment.controller;

import com.poppy.common.api.RspTemplate;
import com.poppy.domain.payment.service.PaymentService;
import com.poppy.domain.reservation.dto.response.ReservationRspDto;
import com.poppy.domain.reservation.entity.Reservation;
import com.poppy.domain.reservation.service.ReservationFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {
    private final ReservationFacade reservationFacade;
    private final PaymentService paymentService;

    @GetMapping("/success")
    public RspTemplate<ReservationRspDto> paymentSuccess(
            @RequestParam String paymentKey,
            @RequestParam String orderId,
            @RequestParam Long amount) {

        // 결제 승인 요청
        paymentService.processPayment(paymentKey, orderId, amount);

        // 예약 완료 처리
        Reservation reservation = reservationFacade.completeReservation(orderId);

        return new RspTemplate<>(HttpStatus.OK, "결제 및 예약이 완료되었습니다.", ReservationRspDto.from(reservation));
    }

    @GetMapping("/fail")
    public RspTemplate<Void> paymentFail(
            @RequestParam String code,
            @RequestParam String message,
            @RequestParam String orderId) {
        paymentService.handlePaymentFailure(orderId);
        return new RspTemplate<>(HttpStatus.BAD_REQUEST, "결제에 실패했습니다: " + message);
    }
}
