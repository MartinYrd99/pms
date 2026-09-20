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
import com.pms.parking.request.StartParkingSessionRequest;
import com.pms.parking.response.ParkingSessionResponse;
import com.pms.vehicle.core.Vehicle;
import com.pms.vehicle.core.VehicleRepository;
import com.pms.zone.core.Zone;
import com.pms.zone.core.ZoneRepository;
import com.pms.zone.core.tariff.Tariff;
import com.pms.zone.core.tariff.TariffRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ParkingSessionStartTest extends AbstractPostgresIT {
    private static final Instant FIXED_NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
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

    @Test
    void startingInSeededActiveZoneRecordsSessionWithOwnerAndCurrentTariff() throws Exception {
        User owner = createUser("owner");
        Vehicle vehicle = registerVehicle(owner);
        Zone blueZone = zoneByName("Blue Zone");
        Tariff currentTariff = tariffRepository.findByZoneIdAndValidToIsNull(blueZone.getId()).orElseThrow();

        String responseBody = mockMvc.perform(post("/api/v1/parking-sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartParkingSessionRequest(vehicle.getId(), blueZone.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.vehicle.id").value(vehicle.getId()))
                .andExpect(jsonPath("$.zone.id").value(blueZone.getId()))
                .andExpect(jsonPath("$.startedAt").exists())
                .andReturn().getResponse().getContentAsString();

        ParkingSessionResponse created = objectMapper.readValue(responseBody, ParkingSessionResponse.class);
        ParkingSession stored = parkingSessionRepository.findById(created.id()).orElseThrow();

        assertThat(stored.getStartedAt()).isEqualTo(FIXED_NOW);
        assertThat(stored.getEndedAt()).isNull();
        assertThat(stored.getAmount()).isNull();
        assertThat(stored.getPaidAt()).isNull();
        assertThat(stored.getUserId()).isEqualTo(owner.getId());
        assertThat(stored.getTariffId()).isEqualTo(currentTariff.getId());
    }

    @Test
    void startingWithAnotherUsersVehicleReturns403() throws Exception {
        User owner = createUser("owner");
        User requester = createUser("requester");
        Vehicle vehicle = registerVehicle(owner);
        Zone blueZone = zoneByName("Blue Zone");

        mockMvc.perform(post("/api/v1/parking-sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(requester))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartParkingSessionRequest(vehicle.getId(), blueZone.getId()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("validation.vehicle.not-owned"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void startingWithUnknownZoneReturns404() throws Exception {
        User owner = createUser("owner");
        Vehicle vehicle = registerVehicle(owner);

        mockMvc.perform(post("/api/v1/parking-sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartParkingSessionRequest(vehicle.getId(), Long.MAX_VALUE))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("validation.zone.not-found"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void startingInTheSeededInactiveZoneReturns409() throws Exception {
        User owner = createUser("owner");
        Vehicle vehicle = registerVehicle(owner);
        Zone greyZone = zoneByName("Grey Zone");

        mockMvc.perform(post("/api/v1/parking-sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartParkingSessionRequest(vehicle.getId(), greyZone.getId()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("validation.zone.inactive"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void secondStartForAVehicleWithAnActiveSessionReturns409WithBlockingSessionId() throws Exception {
        User owner = createUser("owner");
        Vehicle vehicle = registerVehicle(owner);
        Zone blueZone = zoneByName("Blue Zone");
        Tariff currentTariff = tariffRepository.findByZoneIdAndValidToIsNull(blueZone.getId()).orElseThrow();

        ParkingSession activeSession = parkingSessionRepository.save(new ParkingSession()
                .setUserId(owner.getId())
                .setVehicleId(vehicle.getId())
                .setZoneId(blueZone.getId())
                .setTariffId(currentTariff.getId())
                .setStartedAt(FIXED_NOW));

        mockMvc.perform(post("/api/v1/parking-sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartParkingSessionRequest(vehicle.getId(), blueZone.getId()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("validation.parking-session.unsettled-exists"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.blockingSessionId").value(activeSession.getId()))
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void secondStartForAVehicleWithAnEndedButUnpaidSessionReturns409WithBlockingSessionId() throws Exception {
        User owner = createUser("owner");
        Vehicle vehicle = registerVehicle(owner);
        Zone blueZone = zoneByName("Blue Zone");
        Tariff currentTariff = tariffRepository.findByZoneIdAndValidToIsNull(blueZone.getId()).orElseThrow();

        ParkingSession endedUnpaidSession = parkingSessionRepository.save(new ParkingSession()
                .setUserId(owner.getId())
                .setVehicleId(vehicle.getId())
                .setZoneId(blueZone.getId())
                .setTariffId(currentTariff.getId())
                .setStartedAt(FIXED_NOW.minusSeconds(7_200))
                .setEndedAt(FIXED_NOW)
                .setAmount(new BigDecimal("4.00")));

        mockMvc.perform(post("/api/v1/parking-sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartParkingSessionRequest(vehicle.getId(), blueZone.getId()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("validation.parking-session.unsettled-exists"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.blockingSessionId").value(endedUnpaidSession.getId()))
                .andExpect(jsonPath("$.length()").value(3));
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