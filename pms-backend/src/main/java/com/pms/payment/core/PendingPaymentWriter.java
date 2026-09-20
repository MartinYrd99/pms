package com.pms.payment.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts a pending payment in its own transaction, separate from the caller's. A racing insert
 * that loses to the partial unique index must abort only this transaction — never the caller's —
 * so the caller is left with a clean persistence context it can use to re-read the winning row.
 */
@Component
@Slf4j
@RequiredArgsConstructor
class PendingPaymentWriter {
    private final PaymentRepository paymentRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Payment insert(Payment pending) {
        log.info("Inserting a PENDING payment for session {} in its own transaction", pending.getSessionId());

        return paymentRepository.save(pending);
    }
}
