package com.pms.payment.core.settlement;

import com.pms.parking.core.ParkingSessionRepository;
import com.pms.payment.core.PaymentStatus;
import com.pms.payment.core.Payment;
import com.pms.payment.core.PaymentRepository;
import com.pms.payment.core.provider.PaymentProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claim-then-charge settlement, one payment at a time: the due row is locked ({@code FOR UPDATE
 * SKIP LOCKED}) before the provider is ever called, so two concurrent runners can never charge
 * the same payment twice, and a poison payment's transaction can never roll back another's.
 */
@Service
@Slf4j
public class PaymentSettlementService {
    private static final int MAX_ATTEMPTS = 3;

    private final PaymentRepository paymentRepository;
    private final ParkingSessionRepository parkingSessionRepository;
    private final PaymentProvider paymentProvider;
    private final Clock clock;
    private final Duration claimDelay;

    public PaymentSettlementService(
            PaymentRepository paymentRepository,
            ParkingSessionRepository parkingSessionRepository,
            PaymentProvider paymentProvider,
            Clock clock,
            @Value("${pms.payment.settlement.claim-delay-ms:3000}") long claimDelayMs) {
        this.paymentRepository = paymentRepository;
        this.parkingSessionRepository = parkingSessionRepository;
        this.paymentProvider = paymentProvider;
        this.clock = clock;
        this.claimDelay = Duration.ofMillis(claimDelayMs);
    }

    /**
     * Claims and settles at most one due payment. Returns whether one was found, purely so a
     * caller can tell an empty queue from a settled one; the queue is the {@code payments} table
     * itself, never an in-memory timer, so a restart with rows still {@code PENDING} loses nothing.
     */
    @Transactional
    public boolean settleNextPending() {
        return settleNextPending(Set.of()).isPresent();
    }

    /**
     * Same claim-then-charge attempt, excluding payments a caller has already attempted earlier in
     * the same run, so a payment that stays {@code PENDING} after a failed attempt is picked up by a
     * later run rather than reclaimed within the same one. Returns the claimed payment, reflecting the outcome of the attempt,
     * or empty if no due payment remained.
     */
    @Transactional
    Optional<Payment> settleNextPending(Set<Long> excludedPaymentIds) {
        Optional<Payment> claimed = excludedPaymentIds.isEmpty()
                ? paymentRepository.claimNextPending(clock.instant().minus(claimDelay))
                : paymentRepository.claimNextPending(clock.instant().minus(claimDelay), excludedPaymentIds);

        claimed.ifPresent(payment -> {
            log.info("Claimed payment {} of session {} for settlement (attempt {} of {})",
                    payment.getId(), payment.getSessionId(), payment.getAttempts() + 1, MAX_ATTEMPTS);

            settle(payment);
        });

        return claimed;
    }

    /**
     * Records a failed attempt for a payment whose settlement threw, in a transaction of its own:
     * the settlement transaction that claimed the row rolled back entirely, so without this
     * out-of-band write the row's {@code attempts} would never advance and, being the oldest due
     * row, it would be reclaimed and re-thrown on every subsequent run, starving the rest of the
     * queue instead of eventually reaching {@code FAILED}. Guarded the same way as the in-transaction
     * path, so a concurrent runner that already resolved the row is never overwritten.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordFailedAttempt(Long paymentId) {
        log.info("Recording a failed settlement attempt for payment {} out of band", paymentId);

        paymentRepository.incrementAttemptsIfPending(paymentId, MAX_ATTEMPTS);
    }

    private void settle(Payment payment) {
        try {
            boolean charged = paymentProvider.charge(payment);

            if (charged) {
                Instant settledAt = clock.instant();

                payment
                        .setAttempts(payment.getAttempts() + 1)
                        .setStatus(PaymentStatus.COMPLETED)
                        .setSettledAt(settledAt);

                paymentRepository.saveAndFlush(payment);
                int markedPaid = parkingSessionRepository.markPaidIfUnpaid(payment.getSessionId(), settledAt);

                log.info("Settled payment {} of {} at {}; marked {} session(s) paid",
                        payment.getId(), payment.getAmount(), settledAt, markedPaid);

                return;
            }

            log.info("Provider declined payment {}; recording the attempt (max {})", payment.getId(), MAX_ATTEMPTS);

            paymentRepository.incrementAttemptsIfPending(payment.getId(), MAX_ATTEMPTS);
        } catch (RuntimeException e) {
            throw new PaymentSettlementFailedException(payment.getId(), e);
        }
    }
}
