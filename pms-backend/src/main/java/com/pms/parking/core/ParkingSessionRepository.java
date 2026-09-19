package com.pms.parking.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ParkingSessionRepository extends JpaRepository<ParkingSession, Long> {
    Optional<ParkingSession> findByVehicleIdAndPaidAtIsNull(Long vehicleId);

    List<ParkingSession> findByUserIdAndEndedAtIsNullOrderByStartedAtDesc(Long userId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE ParkingSession s SET s.endedAt = :endedAt, s.amount = :amount WHERE s.id = :id AND s.endedAt IS NULL")
    int endIfActive(@Param("id") Long id, @Param("endedAt") Instant endedAt, @Param("amount") BigDecimal amount);
}