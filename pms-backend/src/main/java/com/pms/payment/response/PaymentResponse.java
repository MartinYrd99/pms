package com.pms.payment.response;

import com.pms.parking.core.PaymentStatus;
import com.pms.payment.core.Payment;
import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        Long id, Long sessionId, BigDecimal amount, PaymentStatus status, Instant createdAt, Instant settledAt) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getSessionId(),
                payment.getAmount(),
                payment.getStatus(),
                payment.getCreatedAt(),
                payment.getSettledAt()
        );
    }
}