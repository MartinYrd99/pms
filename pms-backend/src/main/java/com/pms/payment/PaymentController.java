package com.pms.payment;

import com.pms.payment.core.PaymentOutcome;
import com.pms.payment.core.PaymentService;
import com.pms.payment.response.PaymentResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/parking-sessions")
@Slf4j
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService paymentService;

    /**
     * Idempotent: a session freshly asked to pay answers 201 with a new PENDING payment; a session
     * that already has a live payment answers 200 with that same payment, never a second row.
     */
    @PostMapping("/{sessionId}/payment")
    public PaymentResponse pay(@AuthenticationPrincipal Long userId, @PathVariable Long sessionId, HttpServletResponse response) {
        log.info("POST /api/v1/parking-sessions/{}/payment for user {}", sessionId, userId);

        PaymentOutcome outcome = paymentService.payFor(userId, sessionId);

        response.setStatus((outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).value());

        log.info("Answering with payment {} for session {} (created={})",
                outcome.payment().getId(), sessionId, outcome.created());

        return PaymentResponse.from(outcome.payment());
    }

    /**
     * Shows the payment the driver is watching: the live one if a payment is in flight, otherwise
     * the most recent one, so a past FAILED payment surfaces and the UI can offer a retry.
     */
    @GetMapping("/{sessionId}/payment")
    @ResponseStatus(HttpStatus.OK)
    public PaymentResponse getPayment(@AuthenticationPrincipal Long userId, @PathVariable Long sessionId) {
        log.info("GET /api/v1/parking-sessions/{}/payment for user {}", sessionId, userId);

        return PaymentResponse.from(paymentService.getPaymentForSession(userId, sessionId));
    }
}
