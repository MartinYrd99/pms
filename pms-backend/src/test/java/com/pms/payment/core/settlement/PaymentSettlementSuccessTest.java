package com.pms.payment.core.settlement;

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
 * Verifies one settlement run against the real (succeeding) simulator: a due PENDING payment is
 * claimed, completed, and stamps its session paid, while a payment younger than the claim delay
 * is left untouched. The claim query scans the whole {@code payments} table, and every IT shares
 * one Postgres container, so any {@code PENDING} row left behind by an earlier test is cleared
 * first to guarantee this test's own payment is the only due candidate.
 */
@SpringBootTest
class PaymentSettlementSuccessTest extends AbstractPostgresIT {
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
    void settlesADuePaymentAndStampsItsSessionPaidButLeavesAYoungOneUntouched() {
        clearLeftoverPendingPayments();

        User owner = createUser("owner");
        ParkingSession dueSession = endedSession(owner);
        ParkingSession youngSession = endedSession(owner);

        Payment due = paymentRepository.save(new Payment()
                .setSessionId(dueSession.getId())
                .setAmount(new BigDecimal("6.00"))
                .setStatus(PaymentStatus.PENDING)
                .setCreatedAt(Instant.now().minusSeconds(10)));

        Payment young = paymentRepository.save(new Payment()
                .setSessionId(youngSession.getId())
                .setAmount(new BigDecimal("6.00"))
                .setStatus(PaymentStatus.PENDING)
                .setCreatedAt(Instant.now()));

        boolean settledSomething = paymentSettlementService.settleNextPending();

        assertThat(settledSomething).isTrue();

        Payment settledDue = paymentRepository.findById(due.getId()).orElseThrow();
        assertThat(settledDue.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(settledDue.getSettledAt()).isNotNull();
        assertThat(settledDue.getAttempts()).isEqualTo(1);

        ParkingSession paidSession = parkingSessionRepository.findById(dueSession.getId()).orElseThrow();
        assertThat(paidSession.getPaidAt()).isNotNull();

        Payment untouchedYoung = paymentRepository.findById(young.getId()).orElseThrow();
        assertThat(untouchedYoung.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(untouchedYoung.getSettledAt()).isNull();
        assertThat(untouchedYoung.getAttempts()).isZero();

        ParkingSession stillUnpaidSession = parkingSessionRepository.findById(youngSession.getId()).orElseThrow();
        assertThat(stillUnpaidSession.getPaidAt()).isNull();
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