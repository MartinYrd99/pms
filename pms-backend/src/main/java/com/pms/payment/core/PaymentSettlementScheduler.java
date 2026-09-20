package com.pms.payment.core;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires roughly every 2s and drains due payments one transaction at a time, up to a bounded cap
 * per tick, so the job's throughput isn't capped at one payment per tick regardless of queue
 * depth. Every payment attempted in a run — settled, still {@code PENDING} after a decline, or a
 * poison row whose settlement threw — is excluded from the rest of that same run's claims, so it
 * is a later tick, never the same one, that reclaims it; a poison row's attempt is instead
 * recorded out of band (its own transaction) so it still advances toward {@code FAILED}.
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "pms.payment.settlement", name = "enabled", havingValue = "true", matchIfMissing = true)
class PaymentSettlementScheduler {
    private final PaymentSettlementService paymentSettlementService;
    private final SettlementLivenessTracker settlementLivenessTracker;
    private final int maxPerRun;

    PaymentSettlementScheduler(
            PaymentSettlementService paymentSettlementService,
            SettlementLivenessTracker settlementLivenessTracker,
            @Value("${pms.payment.settlement.max-per-run:50}") int maxPerRun) {
        this.paymentSettlementService = paymentSettlementService;
        this.settlementLivenessTracker = settlementLivenessTracker;
        this.maxPerRun = maxPerRun;
    }

    @Scheduled(fixedDelayString = "${pms.payment.settlement.fixed-delay-ms:2000}")
    void settleDuePayments() {
        Set<Long> excludedPaymentIds = new HashSet<>();

        for (int processed = 0; processed < maxPerRun; processed++) {
            try {
                Optional<Payment> attempted = paymentSettlementService.settleNextPending(excludedPaymentIds);

                if (attempted.isEmpty()) {
                    settlementLivenessTracker.recordSuccess();
                    return;
                }

                excludedPaymentIds.add(attempted.get().getId());
            } catch (PaymentSettlementFailedException e) {
                log.error("Settlement of payment {} failed; recording the attempt and continuing this run", e.getPaymentId(), e.getCause());

                paymentSettlementService.recordFailedAttempt(e.getPaymentId());

                excludedPaymentIds.add(e.getPaymentId());
            } catch (RuntimeException e) {
                log.error("Settlement run failed; will retry on the next tick", e);

                return;
            }
        }

        settlementLivenessTracker.recordSuccess();
    }
}