package com.pms.parking.core;

import com.pms.error.ForbiddenException;
import com.pms.vehicle.core.Vehicle;
import com.pms.vehicle.core.VehicleRepository;
import com.pms.zone.core.Tariff;
import com.pms.zone.core.TariffRepository;
import com.pms.zone.core.Zone;
import com.pms.zone.core.ZoneRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ParkingSessionService {
    private static final String VEHICLE_NOT_FOUND_CODE = "validation.vehicle.not-found";
    private static final String VEHICLE_NOT_OWNED_CODE = "validation.vehicle.not-owned";
    private static final String ZONE_NOT_FOUND_CODE = "validation.zone.not-found";
    private static final String ZONE_INACTIVE_CODE = "validation.zone.inactive";
    private static final String ZONE_TARIFF_MISSING_CODE = "validation.zone.tariff-missing";
    private static final String UNSETTLED_SESSION_CODE = "validation.parking-session.unsettled-exists";

    private final VehicleRepository vehicleRepository;
    private final ZoneRepository zoneRepository;
    private final TariffRepository tariffRepository;
    private final ParkingSessionRepository parkingSessionRepository;
    private final Clock clock;

    public StartedSession start(Long userId, Long vehicleId, Long zoneId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new EntityNotFoundException(VEHICLE_NOT_FOUND_CODE));

        if (!vehicle.getUserId().equals(userId)) {
            throw new ForbiddenException(VEHICLE_NOT_OWNED_CODE);
        }

        Zone zone = zoneRepository.findById(zoneId)
                .orElseThrow(() -> new EntityNotFoundException(ZONE_NOT_FOUND_CODE));

        if (!zone.isActive()) {
            throw new IllegalStateException(ZONE_INACTIVE_CODE);
        }

        Tariff tariff = tariffRepository.findByZoneIdAndValidToIsNull(zoneId)
                .orElseThrow(() -> new IllegalStateException(ZONE_TARIFF_MISSING_CODE));

        parkingSessionRepository.findByVehicleIdAndPaidAtIsNull(vehicleId).ifPresent(this::rejectAsUnsettled);

        ParkingSession session = new ParkingSession()
                .setUserId(vehicle.getUserId())
                .setVehicleId(vehicleId)
                .setZoneId(zoneId)
                .setTariffId(tariff.getId())
                .setStartedAt(clock.instant());

        try {
            session = parkingSessionRepository.save(session);
        } catch (DataIntegrityViolationException e) {
            throw new UnsettledSessionException(UNSETTLED_SESSION_CODE, null);
        }

        return new StartedSession(session, vehicle, zone);
    }

    private void rejectAsUnsettled(ParkingSession blockingSession) {
        throw new UnsettledSessionException(UNSETTLED_SESSION_CODE, blockingSession.getId());
    }

    @Transactional(readOnly = true)
    public List<StartedSession> listActive(Long userId) {
        List<ParkingSession> sessions = parkingSessionRepository.findByUserIdAndEndedAtIsNullOrderByStartedAtDesc(userId);

        Map<Long, Vehicle> vehiclesById = vehicleRepository
                .findAllById(sessions.stream().map(ParkingSession::getVehicleId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Vehicle::getId, Function.identity()));

        Map<Long, Zone> zonesById = zoneRepository
                .findAllById(sessions.stream().map(ParkingSession::getZoneId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Zone::getId, Function.identity()));

        return sessions.stream()
                .map(session -> new StartedSession(session, vehiclesById.get(session.getVehicleId()), zonesById.get(session.getZoneId())))
                .toList();
    }
}