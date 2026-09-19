package com.pms.parking.core;

import com.pms.error.ForbiddenException;
import com.pms.vehicle.core.Vehicle;
import com.pms.vehicle.core.VehicleRepository;
import com.pms.zone.core.Tariff;
import com.pms.zone.core.TariffRepository;
import com.pms.zone.core.Zone;
import com.pms.zone.core.ZoneRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
    private static final String SESSION_NOT_FOUND_CODE = "validation.parking-session.not-found";
    private static final String SESSION_NOT_OWNED_CODE = "validation.parking-session.not-owned";
    private static final String SESSION_TARIFF_MISSING_CODE = "validation.parking-session.tariff-missing";
    private static final String SESSION_ALREADY_ENDED_CODE = "validation.parking-session.already-ended";

    private final VehicleRepository vehicleRepository;
    private final ZoneRepository zoneRepository;
    private final TariffRepository tariffRepository;
    private final ParkingSessionRepository parkingSessionRepository;
    private final PricingService pricingService;
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

    /**
     * Ends a session and fixes its amount.
     */
    public StartedSession end(Long userId, Long sessionId) {
        ParkingSession session = loadOwnedSession(userId, sessionId);

        Tariff tariff = tariffRepository.findById(session.getTariffId())
                .orElseThrow(() -> new EntityNotFoundException(SESSION_TARIFF_MISSING_CODE));

        Instant endedAt = clock.instant();
        BigDecimal amount = pricingService.price(session.getStartedAt(), endedAt, tariff);
        int updated = parkingSessionRepository.endIfActive(sessionId, endedAt, amount);

        if (updated == 0) {
            throw new SessionAlreadyEndedException(SESSION_ALREADY_ENDED_CODE, loadBundle(loadSession(sessionId)));
        }

        return loadBundle(session.setEndedAt(endedAt).setAmount(amount));
    }

    @Transactional(readOnly = true)
    public StartedSession get(Long userId, Long sessionId) {
        return loadBundle(loadOwnedSession(userId, sessionId));
    }

    private ParkingSession loadOwnedSession(Long userId, Long sessionId) {
        ParkingSession session = loadSession(sessionId);

        if (!session.getUserId().equals(userId)) {
            throw new ForbiddenException(SESSION_NOT_OWNED_CODE);
        }

        return session;
    }

    private ParkingSession loadSession(Long sessionId) {
        return parkingSessionRepository.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException(SESSION_NOT_FOUND_CODE));
    }

    private StartedSession loadBundle(ParkingSession session) {
        Vehicle vehicle = vehicleRepository.findById(session.getVehicleId())
                .orElseThrow(() -> new EntityNotFoundException(VEHICLE_NOT_FOUND_CODE));
        Zone zone = zoneRepository.findById(session.getZoneId())
                .orElseThrow(() -> new EntityNotFoundException(ZONE_NOT_FOUND_CODE));

        return new StartedSession(session, vehicle, zone);
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