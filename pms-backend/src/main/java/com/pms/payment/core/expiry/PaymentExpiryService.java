package com.pms.payment.core.expiry;

import com.pms.payment.core.PaymentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A payment cannot stay {@code PENDING} forever: past the configured age it is moved straight to
 * {@code FAILED} in one guarded bulk update, regardless of its attempt count, so a driver gets an
 * actionable failure instead of an indefinite spinner. The session stays unpaid and the vehicle
 * blocked until a retry succeeds — {@code paid_at} is only ever written by a completed settlement.
 */
@Service
@Slf4j
@Transactional
public class PaymentExpiryService {
    private final PaymentRepository paymentRepository;
    private final Clock clock;
    private final Duration expiryAge;

    public PaymentExpiryService(
            PaymentRepository paymentRepository,
            Clock clock,
            @Value("${pms.payment.expiry.age-ms:900000}") long expiryAgeMs) {
        this.paymentRepository = paymentRepository;
        this.clock = clock;
        this.expiryAge = Duration.ofMillis(expiryAgeMs);

        log.info("Payments still PENDING after {} will be expired to FAILED", this.expiryAge);
    }

    /**
     * Expires every payment still {@code PENDING} past the configured age and logs how many rows
     * it flipped; a {@code COMPLETED} or already-{@code FAILED} payment is never touched.
     */
    public void expireStalePending() {
        Instant cutoff = clock.instant().minus(expiryAge);
        int expired = paymentRepository.expirePendingOlderThan(cutoff);

        if (expired > 0) {
            log.info("Expired {} PENDING payment(s) created before {} to FAILED", expired, cutoff);
        }
    }
}
