package com.pms.payment.core;

import lombok.Getter;

@Getter
class PaymentSettlementFailedException extends RuntimeException {
    private final Long paymentId;

    PaymentSettlementFailedException(Long paymentId, Throwable cause) {
        super(cause);

        this.paymentId = paymentId;
    }
}
