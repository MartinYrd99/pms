package com.pms.payment.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.pms.AbstractPostgresIT;
import com.pms.auth.core.User;
import com.pms.auth.core.UserRepository;
import com.pms.parking.core.ParkingSession;
import com.pms.parking.core.ParkingSessionRepository;
import com.pms.parking.core.PaymentStatus;
import com.pms.vehicle.core.Vehicle;
import com.pms.vehicle.core.VehicleRepository;
import com.pms.zone.core.Tariff;
import com.pms.zone.core.TariffRepository;
import com.pms.zone.core.Zone;
import com.pms.zone.core.ZoneRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies settlement against a provider forced to decline every charge: attempts climb by one on
 * each run while the payment stays {@code PENDING}, then flips to {@code FAILED} on the run where
 * attempts reaches 3 — and the session is never stamped paid along the way.
 */
@SpringBootTest
@TestPropertySource(properties = "pms.payment.simulator.fail=true")
class PaymentSettlementFailureTest extends AbstractPostgresIT {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private PaymentSettlementService paymentSettlementService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ParkingSessionRepository parkingSessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private ZoneRepository zoneRepository;

    @Autowired
    private TariffRepository tariffRepository;

    @Test
    void declinedChargesIncrementAttemptsAndFailOnTheThirdRunWithoutEverPayingTheSession() {
        clearLeftoverPendingPayments();

        User owner = createUser("owner");
        ParkingSession session = endedSession(owner);

        Payment payment = paymentRepository.save(new Payment()
                .setSessionId(session.getId())
                .setAmount(new BigDecimal("6.00"))
                .setStatus(PaymentStatus.PENDING)
                .setCreatedAt(Instant.now().minusSeconds(10)));

        paymentSettlementService.settleNextPending();
        assertPending(payment.getId(), 1);
        assertThat(parkingSessionRepository.findById(session.getId()).orElseThrow().getPaidAt()).isNull();

        paymentSettlementService.settleNextPending();
        assertPending(payment.getId(), 2);
        assertThat(parkingSessionRepository.findById(session.getId()).orElseThrow().getPaidAt()).isNull();

        paymentSettlementService.settleNextPending();
        Payment failed = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(3);
        assertThat(failed.getSettledAt()).isNull();
        assertThat(parkingSessionRepository.findById(session.getId()).orElseThrow().getPaidAt()).isNull();
    }

    private void assertPending(Long paymentId, int expectedAttempts) {
        Payment reloaded = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reloaded.getAttempts()).isEqualTo(expectedAttempts);
        assertThat(reloaded.getSettledAt()).isNull();
    }

    /**
     * The claim query is not session-scoped; a {@code PENDING} row left behind by another test
     * sharing this Postgres container would otherwise be claimed ahead of this test's own payment.
     */
    private void clearLeftoverPendingPayments() {
        paymentRepository.findAll().stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.PENDING)
                .forEach(payment -> paymentRepository.save(payment.setStatus(PaymentStatus.FAILED)));
    }

    private ParkingSession endedSession(User owner) {
        Vehicle vehicle = registerVehicle(owner);
        Zone blueZone = zoneByName("Blue Zone");
        Tariff blueTariff = tariffRepository.findByZoneIdAndValidToIsNull(blueZone.getId()).orElseThrow();

        return parkingSessionRepository.save(new ParkingSession()
                .setUserId(owner.getId())
                .setVehicleId(vehicle.getId())
                .setZoneId(blueZone.getId())
                .setTariffId(blueTariff.getId())
                .setStartedAt(NOW)
                .setEndedAt(NOW.plus(Duration.ofHours(1)))
                .setAmount(new BigDecimal("6.00")));
    }

    private Zone zoneByName(String name) {
        return zoneRepository.findAll().stream()
                .filter(zone -> zone.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private Vehicle registerVehicle(User owner) {
        return vehicleRepository.save(new Vehicle()
                .setUserId(owner.getId())
                .setPlate("PLATE-" + UUID.randomUUID())
                .setBrand("Toyota")
                .setModel("Corolla"));
    }

    private User createUser(String usernamePrefix) {
        User user = new User()
                .setUsername(usernamePrefix + "-" + UUID.randomUUID())
                .setPasswordHash(passwordEncoder.encode("irrelevant-password-1"));

        return userRepository.save(user);
    }
}
