package com.pms.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.pms.payment.response.PaymentResponse;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies the Pay endpoint: paying an ended session creates exactly one PENDING payment carrying
 * the session's stored amount, a repeat call is idempotent, active/foreign/unknown sessions are
 * refused, and a previous FAILED payment never blocks a fresh one.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentCreateTest extends AbstractPostgresIT {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    void payingAnEndedSessionCreatesAPendingPaymentAndARepeatCallReturnsTheSameOne() throws Exception {
        User owner = createUser("owner");
        ParkingSession session = endedSession(owner, new BigDecimal("6.00"));

        String firstBody = mockMvc.perform(post("/api/v1/parking-sessions/{id}/payment", session.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value(session.getId()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(6.00))
                .andReturn().getResponse().getContentAsString();

        PaymentResponse created = objectMapper.readValue(firstBody, PaymentResponse.class);

        String secondBody = mockMvc.perform(post("/api/v1/parking-sessions/{id}/payment", session.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.id()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        PaymentResponse repeated = objectMapper.readValue(secondBody, PaymentResponse.class);
        assertThat(repeated.id()).isEqualTo(created.id());
        assertThat(paymentsForSession(session.getId())).hasSize(1);
    }

    @Test
    void payingAnActiveSessionReturns409AndWritesNoPaymentRow() throws Exception {
        User owner = createUser("owner");
        Vehicle vehicle = registerVehicle(owner);
        Zone blueZone = zoneByName("Blue Zone");
        Tariff blueTariff = tariffRepository.findByZoneIdAndValidToIsNull(blueZone.getId()).orElseThrow();

        ParkingSession activeSession = parkingSessionRepository.save(new ParkingSession()
                .setUserId(owner.getId())
                .setVehicleId(vehicle.getId())
                .setZoneId(blueZone.getId())
                .setTariffId(blueTariff.getId())
                .setStartedAt(NOW));

        mockMvc.perform(post("/api/v1/parking-sessions/{id}/payment", activeSession.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("validation.parking-session.active"))
                .andExpect(jsonPath("$.message").isString());

        assertThat(paymentsForSession(activeSession.getId())).isEmpty();
    }

    @Test
    void payingAnotherUsersSessionReturns403() throws Exception {
        User owner = createUser("owner");
        User requester = createUser("requester");
        ParkingSession session = endedSession(owner, new BigDecimal("6.00"));

        mockMvc.perform(post("/api/v1/parking-sessions/{id}/payment", session.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(requester)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("validation.parking-session.not-owned"))
                .andExpect(jsonPath("$.message").isString());

        assertThat(paymentsForSession(session.getId())).isEmpty();
    }

    @Test
    void payingAnUnknownSessionReturns404() throws Exception {
        User owner = createUser("owner");

        mockMvc.perform(post("/api/v1/parking-sessions/{id}/payment", Long.MAX_VALUE)
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("validation.parking-session.not-found"))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void aSessionWhoseOnlyPaymentFailedAcceptsAFreshPaymentAndKeepsTheFailedRow() throws Exception {
        User owner = createUser("owner");
        ParkingSession session = endedSession(owner, new BigDecimal("6.00"));

        Payment failed = paymentRepository.save(new Payment()
                .setSessionId(session.getId())
                .setAmount(new BigDecimal("6.00"))
                .setStatus(PaymentStatus.FAILED)
                .setCreatedAt(NOW));

        String body = mockMvc.perform(post("/api/v1/parking-sessions/{id}/payment", session.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        PaymentResponse created = objectMapper.readValue(body, PaymentResponse.class);
        assertThat(created.id()).isNotEqualTo(failed.getId());

        List<Payment> stored = paymentsForSession(session.getId());
        assertThat(stored).hasSize(2);
        assertThat(paymentRepository.findById(failed.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    private List<Payment> paymentsForSession(Long sessionId) {
        return paymentRepository.findAll().stream().filter(payment -> payment.getSessionId().equals(sessionId)).toList();
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