package com.pms.parking;

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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies that the session-detail endpoint and the history list both expose {@code paymentStatus}
 * using the same "live payment, else most recent" rule the payment endpoint applies, with
 * {@code null} for a session nobody ever tried to pay.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ParkingSessionPaymentStatusTest extends AbstractPostgresIT {
    private static final Instant FIXED_NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

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
    void detailAndHistoryBothExposeThePaymentStatusUsingTheSameSelectionRule() throws Exception {
        User owner = createUser("owner");
        Zone blueZone = zoneByName("Blue Zone");
        Tariff blueTariff = tariffRepository.findByZoneIdAndValidToIsNull(blueZone.getId()).orElseThrow();

        ParkingSession neverPaid = endedSession(owner, blueZone, blueTariff, FIXED_NOW.minusSeconds(3 * 3_600));

        ParkingSession failedThenPending = endedSession(owner, blueZone, blueTariff, FIXED_NOW.minusSeconds(2 * 3_600));
        paymentRepository.save(new Payment()
                .setSessionId(failedThenPending.getId())
                .setAmount(new BigDecimal("6.00"))
                .setStatus(PaymentStatus.FAILED)
                .setCreatedAt(FIXED_NOW));
        paymentRepository.save(new Payment()
                .setSessionId(failedThenPending.getId())
                .setAmount(new BigDecimal("6.00"))
                .setStatus(PaymentStatus.PENDING)
                .setCreatedAt(FIXED_NOW.plus(Duration.ofMinutes(1))));

        ParkingSession onlyFailed = endedSession(owner, blueZone, blueTariff, FIXED_NOW.minusSeconds(3_600));
        paymentRepository.save(new Payment()
                .setSessionId(onlyFailed.getId())
                .setAmount(new BigDecimal("6.00"))
                .setStatus(PaymentStatus.FAILED)
                .setCreatedAt(FIXED_NOW));

        mockMvc.perform(get("/api/v1/parking-sessions/{id}", neverPaid.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value(nullValue()));

        mockMvc.perform(get("/api/v1/parking-sessions/{id}", failedThenPending.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));

        mockMvc.perform(get("/api/v1/parking-sessions/{id}", onlyFailed.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("FAILED"));

        mockMvc.perform(get("/api/v1/parking-sessions")
                        .param("page", "0")
                        .param("size", "10")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(onlyFailed.getId()))
                .andExpect(jsonPath("$.content[0].paymentStatus").value("FAILED"))
                .andExpect(jsonPath("$.content[1].id").value(failedThenPending.getId()))
                .andExpect(jsonPath("$.content[1].paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.content[2].id").value(neverPaid.getId()))
                .andExpect(jsonPath("$.content[2].paymentStatus").value(nullValue()));
    }

    private ParkingSession endedSession(User owner, Zone zone, Tariff tariff, Instant startedAt) {
        return parkingSessionRepository.save(new ParkingSession()
                .setUserId(owner.getId())
                .setVehicleId(registerVehicle(owner).getId())
                .setZoneId(zone.getId())
                .setTariffId(tariff.getId())
                .setStartedAt(startedAt)
                .setEndedAt(startedAt.plus(Duration.ofHours(1)))
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

    private String bearerTokenFor(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }
}
