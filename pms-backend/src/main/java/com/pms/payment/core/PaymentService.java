package com.pms.payment.core;

import static java.util.Objects.isNull;

import com.pms.error.ForbiddenException;
import com.pms.parking.core.ParkingSession;
import com.pms.parking.core.ParkingSessionRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {
    private static final List<PaymentStatus> LIVE_STATUSES = List.of(PaymentStatus.PENDING, PaymentStatus.COMPLETED);
    private static final String SESSION_NOT_FOUND_CODE = "validation.parking-session.not-found";
    private static final String SESSION_NOT_OWNED_CODE = "validation.parking-session.not-owned";
    private static final String SESSION_ACTIVE_CODE = "validation.parking-session.active";
    private static final String PAYMENT_NOT_FOUND_CODE = "validation.payment.not-found";

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
    @Transactional
    public PaymentOutcome payFor(Long userId, Long sessionId) {
        log.info("Recording a payment request from user {} for session {}", userId, sessionId);

        ParkingSession session = loadOwnedEndedSession(userId, sessionId);

        Optional<Payment> live = paymentRepository.findBySessionIdAndStatusIn(sessionId, LIVE_STATUSES);

        if (live.isPresent()) {
            log.info("Session {} already carries the live payment {} in status {}; handing it back",
                    sessionId, live.get().getId(), live.get().getStatus());

            return new PaymentOutcome(live.get(), false);
        }

        Payment pending = new Payment()
                .setSessionId(sessionId)
                .setAmount(session.getAmount())
                .setStatus(PaymentStatus.PENDING)
                .setCreatedAt(clock.instant());

        try {
            Payment created = pendingPaymentWriter.insert(pending);

            log.info("Created PENDING payment {} for session {} of {}", created.getId(), sessionId, created.getAmount());

            return new PaymentOutcome(created, true);
        } catch (DataIntegrityViolationException e) {
            log.info("Payment insert for session {} lost the race for the live-payment index; re-reading the winner", sessionId);

            return new PaymentOutcome(
                    paymentRepository.findBySessionIdAndStatusIn(sessionId, LIVE_STATUSES).orElseThrow(() -> e), false
            );
        }
    }

    /**
     * The payment a driver watches for a session: the live one (PENDING/COMPLETED) if an attempt
     * is in flight, otherwise the most recent attempt — which is how a FAILED payment surfaces and
     * the UI can offer a retry. A session nobody ever tried to pay has no payment row at all.
     */
    @Transactional(readOnly = true)
    public Payment getPaymentForSession(Long userId, Long sessionId) {
        log.info("Reading the payment of session {} for user {}", sessionId, userId);

        ParkingSession session = parkingSessionRepository.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException(SESSION_NOT_FOUND_CODE));

        if (!session.getUserId().equals(userId)) {
            log.info("Access refused: session {} belongs to user {}, not to user {}", sessionId, session.getUserId(), userId);

            throw new ForbiddenException(SESSION_NOT_OWNED_CODE);
        }

        Payment payment = selectPayment(sessionId).orElseThrow(() -> new EntityNotFoundException(PAYMENT_NOT_FOUND_CODE));

        log.info("Session {} is watching payment {} in status {}", sessionId, payment.getId(), payment.getStatus());

        return payment;
    }

    /**
     * Bulk form of the same live-else-most-recent rule, used to enrich a page of sessions with a
     * single query instead of one payment lookup per row.
     */
    @Transactional(readOnly = true)
    public Map<Long, PaymentStatus> statusesBySessionId(Collection<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, PaymentStatus> statuses = paymentRepository.findBySessionIdIn(sessionIds).stream()
                .collect(Collectors.groupingBy(Payment::getSessionId))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> selectAmong(entry.getValue()).getStatus()));

        log.info("Resolved payment statuses for {} of {} requested session(s)", statuses.size(), sessionIds.size());

        return statuses;
    }

    private Optional<Payment> selectPayment(Long sessionId) {
        return paymentRepository.findBySessionIdAndStatusIn(sessionId, LIVE_STATUSES)
                .or(() -> paymentRepository.findFirstBySessionIdOrderByCreatedAtDesc(sessionId));
    }

    private Payment selectAmong(List<Payment> payments) {
        return payments.stream()
                .filter(payment -> LIVE_STATUSES.contains(payment.getStatus()))
                .findFirst()
                .orElseGet(() -> payments.stream().max(Comparator.comparing(Payment::getCreatedAt)).orElseThrow());
    }

    private ParkingSession loadOwnedEndedSession(Long userId, Long sessionId) {
        ParkingSession session = parkingSessionRepository.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException(SESSION_NOT_FOUND_CODE));

        if (!session.getUserId().equals(userId)) {
            log.info("Payment refused: session {} belongs to user {}, not to user {}", sessionId, session.getUserId(), userId);

            throw new ForbiddenException(SESSION_NOT_OWNED_CODE);
        }

        if (isNull(session.getEndedAt())) {
            log.info("Payment refused: session {} is still active", sessionId);

            throw new IllegalStateException(SESSION_ACTIVE_CODE);
        }

        return session;
    }
}