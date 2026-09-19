package com.pms.payment.core;

import com.pms.parking.core.PaymentStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findBySessionIdAndStatusIn(Long sessionId, Collection<PaymentStatus> statuses);
}