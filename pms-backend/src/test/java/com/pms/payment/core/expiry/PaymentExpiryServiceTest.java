package com.pms.payment.core.expiry;

import static org.assertj.core.api.Assertions.assertThat;

import com.pms.AbstractPostgresIT;
import com.pms.auth.core.user.User;
import com.pms.auth.core.user.UserRepository;
import com.pms.parking.core.ParkingSession;
import com.pms.parking.core.ParkingSessionRepository;
import com.pms.payment.core.PaymentStatus;
import com.pms.payment.core.Payment;
import com.pms.payment.core.PaymentRepository;
import com.pms.vehicle.core.Vehicle;
import com.pms.vehicle.core.VehicleRepository;
import com.pms.zone.core.Zone;
import com.pms.zone.core.ZoneRepository;
import com.pms.zone.core.tariff.Tariff;
import com.pms.zone.core.tariff.TariffRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Verifies the guarded expiry sweep: a payment stuck {@code PENDING} past the configured age is
 * moved to {@code FAILED} without ever stamping its session paid, a younger {@code PENDING}
 * payment is left untouched, and an already-resolved {@code COMPLETED} payment is never touched
 * regardless of age.
 */
@SpringBootTest
class PaymentExpiryServiceTest extends AbstractPostgresIT {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private PaymentExpiryService paymentExpiryService;

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
    void expiresAStalePendingPaymentButLeavesAYoungOneAlone() {
        User owner = createUser("owner");
        ParkingSession staleSession = endedSession(owner);
        ParkingSession youngSession = endedSession(owner);

        Payment stale = paymentRepository.save(new Payment()
                .setSessionId(staleSession.getId())
                .setAmount(new BigDecimal("6.00"))
                .setStatus(PaymentStatus.PENDING)
                .setCreatedAt(Instant.now().minus(Duration.ofMinutes(16))));

        Payment young = paymentRepository.save(new Payment()
                .setSessionId(youngSession.getId())
                .setAmount(new BigDecimal("6.00"))
                .setStatus(PaymentStatus.PENDING)
                .setCreatedAt(Instant.now().minus(Duration.ofMinutes(1))));

        paymentExpiryService.expireStalePending();

        Payment expiredStale = paymentRepository.findById(stale.getId()).orElseThrow();
        assertThat(expiredStale.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(parkingSessionRepository.findById(staleSession.getId()).orElseThrow().getPaidAt()).isNull();

        Payment untouchedYoung = paymentRepository.findById(young.getId()).orElseThrow();
        assertThat(untouchedYoung.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void neverTouchesAnAlreadyCompletedPaymentRegardlessOfAge() {
        User owner = createUser("owner");
        ParkingSession session = endedSession(owner);
        Instant settledAt = Instant.now().minus(Duration.ofMinutes(20)).truncatedTo(ChronoUnit.MICROS);

        Payment completed = paymentRepository.save(new Payment()
                .setSessionId(session.getId())
                .setAmount(new BigDecimal("6.00"))
                .setStatus(PaymentStatus.COMPLETED)
                .setCreatedAt(Instant.now().minus(Duration.ofMinutes(20)))
                .setSettledAt(settledAt));

        paymentExpiryService.expireStalePending();

        Payment untouched = paymentRepository.findById(completed.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(untouched.getSettledAt()).isEqualTo(settledAt);
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