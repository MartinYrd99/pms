package com.pms.payment.core.expiry;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs independently of the settlement scheduler, so a payment stuck {@code PENDING} still
 * expires even when settlement itself is disabled or unhealthy — the two failure modes this
 * ticket exists to catch separately.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pms.payment.expiry", name = "enabled", havingValue = "true", matchIfMissing = true)
class PaymentExpiryScheduler {
    private final PaymentExpiryService paymentExpiryService;

    @Scheduled(fixedDelayString = "${pms.payment.expiry.fixed-delay-ms:2000}")
    void expireStalePendingPayments() {
        paymentExpiryService.expireStalePending();
    }
}