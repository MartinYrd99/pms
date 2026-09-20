package com.pms.payment;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pms.AbstractPostgresIT;
import com.pms.auth.core.token.JwtService;
import com.pms.auth.core.user.User;
import com.pms.auth.core.user.UserRepository;
import com.pms.parking.core.ParkingSession;
import com.pms.parking.core.ParkingSessionRepository;
import com.pms.payment.core.Payment;
import com.pms.payment.core.PaymentRepository;
import com.pms.payment.core.PaymentStatus;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the payment read endpoint: it reports a live PENDING payment and reflects it turning
 * COMPLETED with a settled timestamp, answers 404 for a session that was never paid, 403 for
 * another user's session, and applies the "live payment, else most recent" selection rule when a
 * session carries a stale FAILED row alongside or instead of a live one.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentStatusReadTest extends AbstractPostgresIT {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private ZoneRepository zoneRepository;

    @Autowired
    private TariffRepository tariffRepository;

    @Autowired
    private ParkingSessionRepository parkingSessionRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void pendingPaymentIsReturnedAndTurnsCompletedWithASettledAtOnceSettled() throws Exception {
        User owner = createUser("owner");
        ParkingSession session = endedSession(owner, new BigDecimal("6.00"));
        Payment pending = paymentRepository.save(new Payment()
                .setSessionId(session.getId())
                .setAmount(new BigDecimal("6.00"))
                .setStatus(PaymentStatus.PENDING)
                .setCreatedAt(NOW));

        mockMvc.perform(get("/api/v1/parking-sessions/{id}/payment", session.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pending.getId()))
                .andExpect(jsonPath("$.sessionId").value(session.getId()))
                .andExpect(jsonPath("$.amount").value(6.00))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.settledAt").value(nullValue()));

        Instant settledAt = NOW.plusSeconds(5);
        paymentRepository.save(pending.setStatus(PaymentStatus.COMPLETED).setSettledAt(settledAt));

        mockMvc.perform(get("/api/v1/parking-sessions/{id}/payment", session.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pending.getId()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.settledAt").value(notNullValue()));
    }

    @Test
    void aSessionWithNoPaymentRowsReturns404() throws Exception {
        User owner = createUser("owner");
        ParkingSession session = endedSession(owner, new BigDecimal("6.00"));

        mockMvc.perform(get("/api/v1/parking-sessions/{id}/payment", session.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("validation.payment.not-found"))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void anotherUsersSessionReturns403() throws Exception {
        User owner = createUser("owner");
        User requester = createUser("requester");
        ParkingSession session = endedSession(owner, new BigDecimal("6.00"));
        paymentRepository.save(new Payment()
                .setSessionId(session.getId())
                .setAmount(new BigDecimal("6.00"))
                .setStatus(PaymentStatus.PENDING)
                .setCreatedAt(NOW));

        mockMvc.perform(get("/api/v1/parking-sessions/{id}/payment", session.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(requester)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("validation.parking-session.not-owned"))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void aStaleFailedRowAlongsideANewerLivePaymentYieldsTheLiveOne() throws Exception {
        User owner = createUser("owner");
        ParkingSession session = endedSession(owner, new BigDecimal("6.00"));
        paymentRepository.save(new Payment()
                .setSessionId(session.getId())
                .setAmount(new BigDecimal("6.00"))
                .setStatus(PaymentStatus.FAILED)
                .setCreatedAt(NOW));
        Payment pending = paymentRepository.save(new Payment()
                .setSessionId(session.getId())
                .setAmount(new BigDecimal("6.00"))
                .setStatus(PaymentStatus.PENDING)
                .setCreatedAt(NOW.plus(Duration.ofMinutes(1))));

        mockMvc.perform(get("/api/v1/parking-sessions/{id}/payment", session.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pending.getId()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void whenOnlyFailedRowsExistTheMostRecentOneIsReturned() throws Exception {
        User owner = createUser("owner");
        ParkingSession session = endedSession(owner, new BigDecimal("6.00"));
        paymentRepository.save(new Payment()
                .setSessionId(session.getId())
                .setAmount(new BigDecimal("6.00"))
                .setStatus(PaymentStatus.FAILED)
                .setCreatedAt(NOW));
        Payment mostRecentFailure = paymentRepository.save(new Payment()
                .setSessionId(session.getId())
                .setAmount(new BigDecimal("6.00"))
                .setStatus(PaymentStatus.FAILED)
                .setCreatedAt(NOW.plus(Duration.ofMinutes(1))));

        mockMvc.perform(get("/api/v1/parking-sessions/{id}/payment", session.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(mostRecentFailure.getId()))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    private ParkingSession endedSession(User owner, BigDecimal amount) {
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
                .setAmount(amount));
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

    private String bearerTokenFor(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }
}
