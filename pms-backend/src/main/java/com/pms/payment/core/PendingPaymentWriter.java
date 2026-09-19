package com.pms.payment.core;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts a pending payment in its own transaction, separate from the caller's. A racing insert
 * that loses to the partial unique index must abort only this transaction — never the caller's —
 * so the caller is left with a clean persistence context it can use to re-read the winning row.
 */
@Component
@RequiredArgsConstructor
class PendingPaymentWriter {
    private final PaymentRepository paymentRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Payment insert(Payment pending) {
        return paymentRepository.save(pending);
    }
}
