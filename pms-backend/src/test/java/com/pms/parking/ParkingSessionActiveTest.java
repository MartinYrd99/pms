package com.pms.parking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pms.AbstractPostgresIT;
import com.pms.auth.core.JwtService;
import com.pms.auth.core.User;
import com.pms.auth.core.UserRepository;
import com.pms.parking.core.ParkingSession;
import com.pms.parking.core.ParkingSessionRepository;
import com.pms.parking.response.ParkingSessionResponse;
import com.pms.vehicle.core.Vehicle;
import com.pms.vehicle.core.VehicleRepository;
import com.pms.zone.core.Tariff;
import com.pms.zone.core.TariffRepository;
import com.pms.zone.core.Zone;
import com.pms.zone.core.ZoneRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
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

@SpringBootTest
@AutoConfigureMockMvc
class ParkingSessionActiveTest extends AbstractPostgresIT {
    private static final Instant FIXED_NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

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
    void userWithTwoParkedVehiclesSeesBothWhileAnotherUserSeesNone() throws Exception {
        User owner = createUser("owner");
        Vehicle firstVehicle = registerVehicle(owner, "First", "Car");
        Vehicle secondVehicle = registerVehicle(owner, "Second", "Car");
        Zone blueZone = zoneByName("Blue Zone");
        Zone greenZone = zoneByName("Green Zone");

        ParkingSession firstSession = startSession(owner, firstVehicle, blueZone, FIXED_NOW.minusSeconds(600));
        ParkingSession secondSession = startSession(owner, secondVehicle, greenZone, FIXED_NOW.minusSeconds(120));

        User otherUser = createUser("other");

        String responseBody = mockMvc.perform(get("/api/v1/parking-sessions/active")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<ParkingSessionResponse> activeSessions =
                Arrays.asList(objectMapper.readValue(responseBody, ParkingSessionResponse[].class));

        assertThat(activeSessions)
                .extracting(
                        ParkingSessionResponse::id,
                        response -> response.vehicle().id(),
                        response -> response.vehicle().plate(),
                        response -> response.zone().id(),
                        ParkingSessionResponse::startedAt)
                .containsExactlyInAnyOrder(
                        tuple(firstSession.getId(), firstVehicle.getId(), firstVehicle.getPlate(), blueZone.getId(), firstSession.getStartedAt()),
                        tuple(secondSession.getId(), secondVehicle.getId(), secondVehicle.getPlate(), greenZone.getId(), secondSession.getStartedAt()));

        String otherUserResponseBody = mockMvc.perform(get("/api/v1/parking-sessions/active")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(otherUser)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readValue(otherUserResponseBody, ParkingSessionResponse[].class)).isEmpty();
    }

    @Test
    void endedSessionDisappearsFromTheActiveListWhileAStillActiveOneRemains() throws Exception {
        User owner = createUser("owner");
        Vehicle endedVehicle = registerVehicle(owner, "Ended", "Car");
        Vehicle activeVehicle = registerVehicle(owner, "Active", "Car");
        Zone blueZone = zoneByName("Blue Zone");

        ParkingSession endedSession = startSession(owner, endedVehicle, blueZone, FIXED_NOW.minusSeconds(7_200));
        parkingSessionRepository.save(endedSession.setEndedAt(FIXED_NOW).setAmount(new BigDecimal("4.00")));
        ParkingSession stillActiveSession = startSession(owner, activeVehicle, blueZone, FIXED_NOW.minusSeconds(300));

        String responseBody = mockMvc.perform(get("/api/v1/parking-sessions/active")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<ParkingSessionResponse> activeSessions =
                Arrays.asList(objectMapper.readValue(responseBody, ParkingSessionResponse[].class));

        assertThat(activeSessions).extracting(ParkingSessionResponse::id).containsExactly(stillActiveSession.getId());
    }

    @Test
    void missingBearerTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/parking-sessions/active")).andExpect(status().isUnauthorized());
    }

    private ParkingSession startSession(User owner, Vehicle vehicle, Zone zone, Instant startedAt) {
        Tariff currentTariff = tariffRepository.findByZoneIdAndValidToIsNull(zone.getId()).orElseThrow();

        return parkingSessionRepository.save(new ParkingSession()
                .setUserId(owner.getId())
                .setVehicleId(vehicle.getId())
                .setZoneId(zone.getId())
                .setTariffId(currentTariff.getId())
                .setStartedAt(startedAt));
    }

    private Zone zoneByName(String name) {
        return zoneRepository.findAll().stream()
                .filter(zone -> zone.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private Vehicle registerVehicle(User owner, String brand, String model) {
        return vehicleRepository.save(new Vehicle()
                .setUserId(owner.getId())
                .setPlate("PLATE-" + UUID.randomUUID())
                .setBrand(brand)
                .setModel(model));
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
