package com.pms.payment.core;

import static java.util.Objects.isNull;

import com.pms.error.ForbiddenException;
import com.pms.parking.core.ParkingSession;
import com.pms.parking.core.ParkingSessionRepository;
import com.pms.parking.core.PaymentStatus;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class PaymentService {
    private static final List<PaymentStatus> LIVE_STATUSES = List.of(PaymentStatus.PENDING, PaymentStatus.COMPLETED);
    private static final String SESSION_NOT_FOUND_CODE = "validation.parking-session.not-found";
    private static final String SESSION_NOT_OWNED_CODE = "validation.parking-session.not-owned";
    private static final String SESSION_ACTIVE_CODE = "validation.parking-session.active";

    private final ParkingSessionRepository parkingSessionRepository;
    private final PaymentRepository paymentRepository;
    private final PendingPaymentWriter pendingPaymentWriter;
    private final Clock clock;

    /**
     * Records that the user asked to pay for their own ended session. Idempotent: a session that
     * already carries a live payment (PENDING or COMPLETED) hands that payment back instead of
     * creating a second one, whether caught by the pre-check or by a racing insert losing to the
     * database's partial unique index.
     */
    public PaymentOutcome payFor(Long userId, Long sessionId) {
        ParkingSession session = loadOwnedEndedSession(userId, sessionId);

        Optional<Payment> live = paymentRepository.findBySessionIdAndStatusIn(sessionId, LIVE_STATUSES);

        if (live.isPresent()) {
            return new PaymentOutcome(live.get(), false);
        }

        Payment pending = new Payment()
                .setSessionId(sessionId)
                .setAmount(session.getAmount())
                .setStatus(PaymentStatus.PENDING)
                .setCreatedAt(clock.instant());

        try {
            return new PaymentOutcome(pendingPaymentWriter.insert(pending), true);
        } catch (DataIntegrityViolationException e) {
            log.info("Payment insert for session {} lost the race for the live-payment index; re-reading the winner", sessionId);

            return new PaymentOutcome(
                    paymentRepository.findBySessionIdAndStatusIn(sessionId, LIVE_STATUSES).orElseThrow(() -> e), false
            );
        }
    }

    private ParkingSession loadOwnedEndedSession(Long userId, Long sessionId) {
        ParkingSession session = parkingSessionRepository.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException(SESSION_NOT_FOUND_CODE));

        if (!session.getUserId().equals(userId)) {
            throw new ForbiddenException(SESSION_NOT_OWNED_CODE);
        }

        if (isNull(session.getEndedAt())) {
            throw new IllegalStateException(SESSION_ACTIVE_CODE);
        }

        return session;
    }
}