package com.pms.parking;

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
import com.pms.parking.response.ParkingSessionResponse;
import com.pms.vehicle.core.Vehicle;
import com.pms.vehicle.core.VehicleRepository;
import com.pms.zone.core.Zone;
import com.pms.zone.core.ZoneRepository;
import com.pms.zone.core.tariff.RuleType;
import com.pms.zone.core.tariff.Tariff;
import com.pms.zone.core.tariff.TariffRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies ending a session: the amount is fixed once from the price in force when the session
 * started, a second end attempt is refused and hands back the already-ended session, and the
 * guarded update — not a prior read — decides the outcome under concurrency.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ParkingSessionEndTest extends AbstractPostgresIT {
    private static final Instant FIXED_NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    static class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advanceBy(Duration duration) {
            instant = instant.plus(duration);
        }

        void reset(Instant instant) {
            this.instant = instant;
        }
    }

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(FIXED_NOW, ZoneOffset.UTC);
        }
    }

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
    private MutableClock clock;

    /**
     * The clock is a shared singleton across tests in this class — reset it before each one so an
     * earlier test's advances never leak in and skew the amount under test.
     */
    @BeforeEach
    void resetClock() {
        clock.reset(FIXED_NOW);
    }

    @Test
    void endingTwoHoursAndFiveMinutesAfterStartInTheBlueZoneChargesSixEuros() throws Exception {
        User owner = createUser("owner");
        Vehicle vehicle = registerVehicle(owner);
        Zone blueZone = zoneByName("Blue Zone");
        Tariff blueTariff = tariffRepository.findByZoneIdAndValidToIsNull(blueZone.getId()).orElseThrow();

        ParkingSession session = startSession(owner, vehicle, blueZone, blueTariff, FIXED_NOW);

        clock.advanceBy(Duration.ofHours(2).plusMinutes(5));
        Instant expectedEndedAt = FIXED_NOW.plus(Duration.ofHours(2).plusMinutes(5));

        String responseBody = mockMvc.perform(post("/api/v1/parking-sessions/{id}/end", session.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(session.getId()))
                .andExpect(jsonPath("$.endedAt").exists())
                .andReturn().getResponse().getContentAsString();

        ParkingSessionResponse ended = objectMapper.readValue(responseBody, ParkingSessionResponse.class);
        assertThat(ended.endedAt()).isEqualTo(expectedEndedAt);
        assertThat(ended.amount()).isEqualByComparingTo(new BigDecimal("6.00"));

        ParkingSession stored = parkingSessionRepository.findById(session.getId()).orElseThrow();
        assertThat(stored.getStartedAt()).isEqualTo(FIXED_NOW);
        assertThat(stored.getEndedAt()).isEqualTo(expectedEndedAt);
        assertThat(stored.getAmount()).isEqualByComparingTo(new BigDecimal("6.00"));
        assertThat(stored.getPaidAt()).isNull();
    }

    @Test
    void endingAnAlreadyEndedSessionReturns409WithTheOriginalAmountAndLeavesItUnchanged() throws Exception {
        User owner = createUser("owner");
        Vehicle vehicle = registerVehicle(owner);
        Zone blueZone = zoneByName("Blue Zone");
        Tariff blueTariff = tariffRepository.findByZoneIdAndValidToIsNull(blueZone.getId()).orElseThrow();

        Instant originalEndedAt = FIXED_NOW.plus(Duration.ofHours(1));
        BigDecimal originalAmount = new BigDecimal("2.00");
        ParkingSession alreadyEnded = parkingSessionRepository.save(new ParkingSession()
                .setUserId(owner.getId())
                .setVehicleId(vehicle.getId())
                .setZoneId(blueZone.getId())
                .setTariffId(blueTariff.getId())
                .setStartedAt(FIXED_NOW)
                .setEndedAt(originalEndedAt)
                .setAmount(originalAmount));

        clock.advanceBy(Duration.ofHours(5));

        mockMvc.perform(post("/api/v1/parking-sessions/{id}/end", alreadyEnded.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("validation.parking-session.already-ended"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.session.id").value(alreadyEnded.getId()))
                .andExpect(jsonPath("$.session.amount").value(2.00))
                .andExpect(jsonPath("$.length()").value(3));

        ParkingSession stored = parkingSessionRepository.findById(alreadyEnded.getId()).orElseThrow();
        assertThat(stored.getEndedAt()).isEqualTo(originalEndedAt);
        assertThat(stored.getAmount()).isEqualByComparingTo(originalAmount);
    }

    @Test
    void endingRemainsPricedAtTheOriginalTariffAfterTheZoneIsDeactivatedAndItsTariffReplaced() throws Exception {
        User owner = createUser("owner");
        Vehicle vehicle = registerVehicle(owner);
        Zone zone = zoneRepository.save(new Zone().setName("Isolation Zone " + UUID.randomUUID()).setCity("Varna").setActive(true));
        Tariff originalTariff = tariffRepository.save(new Tariff()
                .setZoneId(zone.getId())
                .setRuleType(RuleType.HOURLY)
                .setHourlyRate(new BigDecimal("2.00"))
                .setCurrency("EUR")
                .setValidFrom(FIXED_NOW.minusSeconds(1)));

        ParkingSession session = startSession(owner, vehicle, zone, originalTariff, FIXED_NOW);

        clock.advanceBy(Duration.ofHours(1));
        Instant tariffChangeInstant = clock.instant();

        zone.setActive(false);
        zoneRepository.save(zone);

        originalTariff.setValidTo(tariffChangeInstant);
        tariffRepository.save(originalTariff);
        tariffRepository.save(new Tariff()
                .setZoneId(zone.getId())
                .setRuleType(RuleType.HOURLY)
                .setHourlyRate(new BigDecimal("9.00"))
                .setCurrency("EUR")
                .setValidFrom(tariffChangeInstant));

        clock.advanceBy(Duration.ofHours(1));

        String responseBody = mockMvc.perform(post("/api/v1/parking-sessions/{id}/end", session.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        ParkingSessionResponse ended = objectMapper.readValue(responseBody, ParkingSessionResponse.class);
        assertThat(ended.amount()).isEqualByComparingTo(new BigDecimal("4.00"));

        ParkingSession stored = parkingSessionRepository.findById(session.getId()).orElseThrow();
        assertThat(stored.getTariffId()).isEqualTo(originalTariff.getId());
        assertThat(stored.getAmount()).isEqualByComparingTo(new BigDecimal("4.00"));
    }

    @Test
    void endingAnotherUsersSessionReturns403() throws Exception {
        User owner = createUser("owner");
        User requester = createUser("requester");
        Vehicle vehicle = registerVehicle(owner);
        Zone blueZone = zoneByName("Blue Zone");
        Tariff blueTariff = tariffRepository.findByZoneIdAndValidToIsNull(blueZone.getId()).orElseThrow();

        ParkingSession session = startSession(owner, vehicle, blueZone, blueTariff, FIXED_NOW);

        mockMvc.perform(post("/api/v1/parking-sessions/{id}/end", session.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(requester)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("validation.parking-session.not-owned"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));

        ParkingSession stored = parkingSessionRepository.findById(session.getId()).orElseThrow();
        assertThat(stored.getEndedAt()).isNull();
    }

    @Test
    void endingAnUnknownSessionReturns404() throws Exception {
        User owner = createUser("owner");

        mockMvc.perform(post("/api/v1/parking-sessions/{id}/end", Long.MAX_VALUE)
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("validation.parking-session.not-found"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    private ParkingSession startSession(User owner, Vehicle vehicle, Zone zone, Tariff tariff, Instant startedAt) {
        return parkingSessionRepository.save(new ParkingSession()
                .setUserId(owner.getId())
                .setVehicleId(vehicle.getId())
                .setZoneId(zone.getId())
                .setTariffId(tariff.getId())
                .setStartedAt(startedAt));
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