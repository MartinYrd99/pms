package com.pms.parking.core;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ParkingSessionRepository extends JpaRepository<ParkingSession, Long> {
    Optional<ParkingSession> findByVehicleIdAndPaidAtIsNull(Long vehicleId);

    List<ParkingSession> findByUserIdAndEndedAtIsNullOrderByStartedAtDesc(Long userId);
}