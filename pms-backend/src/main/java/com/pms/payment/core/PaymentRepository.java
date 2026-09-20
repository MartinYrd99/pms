package com.pms.payment.core;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findBySessionIdAndStatusIn(Long sessionId, Collection<PaymentStatus> statuses);

    Optional<Payment> findFirstBySessionIdOrderByCreatedAtDesc(Long sessionId);

    List<Payment> findBySessionIdIn(Collection<Long> sessionIds);

    /**
     * Claims the oldest due PENDING payment for settlement: {@code FOR UPDATE SKIP LOCKED} locks
     * the row before any provider call and lets a concurrent runner move straight past it instead
     * of waiting, so two runners can never charge the same payment.
     */
    @Query(value = """
            SELECT * FROM payments
            WHERE status = 'PENDING' AND created_at < :cutoff
            ORDER BY created_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Payment> claimNextPending(@Param("cutoff") Instant cutoff);

    /**
     * Same claim, excluding payments already attempted earlier in the same settlement run: a
     * payment that stayed {@code PENDING} after a failed attempt is still the oldest due row, so
     * without this exclusion the next iteration of the same run would reclaim it immediately
     * instead of a later run picking it up.
     */
    @Query(value = """
            SELECT * FROM payments
            WHERE status = 'PENDING' AND created_at < :cutoff AND id NOT IN (:excludedIds)
            ORDER BY created_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Payment> claimNextPending(@Param("cutoff") Instant cutoff, @Param("excludedIds") Collection<Long> excludedIds);

    /**
     * Atomically advances a payment's failed-attempt count and flips it to {@code FAILED} once the
     * threshold is reached, guarded on {@code status = 'PENDING'} so a concurrent runner that
     * already resolved the row (completed or failed it) is not overwritten by this one.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE payments
            SET attempts = attempts + 1,
                status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE status END
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int incrementAttemptsIfPending(@Param("id") Long id, @Param("maxAttempts") int maxAttempts);

    /**
     * Expires payments stuck {@code PENDING} past the cutoff, guarded on {@code status = 'PENDING'}
     * so a {@code COMPLETED} or already-{@code FAILED} payment, however old, is never touched; this
     * never writes {@code parking_sessions.paid_at} and never deletes a row.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE payments
            SET status = 'FAILED'
            WHERE status = 'PENDING' AND created_at < :cutoff
            """, nativeQuery = true)
    int expirePendingOlderThan(@Param("cutoff") Instant cutoff);
}