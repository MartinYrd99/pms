package com.pms.parking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pms.AbstractPostgresIT;
import com.pms.auth.core.JwtService;
import com.pms.auth.core.User;
import com.pms.auth.core.UserRepository;
import com.pms.parking.core.ParkingSession;
import com.pms.parking.core.ParkingSessionRepository;
import com.pms.parking.request.StartParkingSessionRequest;
import com.pms.vehicle.core.Vehicle;
import com.pms.vehicle.core.VehicleRepository;
import com.pms.zone.core.Tariff;
import com.pms.zone.core.TariffRepository;
import com.pms.zone.core.Zone;
import com.pms.zone.core.ZoneRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the partial unique index — not the service's pre-check — is the final authority: with
 * the pre-check forced to miss an unsettled session that genuinely exists, the real Postgres
 * constraint still rejects the duplicate insert, and the API still answers 409, never 500, leaving
 * exactly the one row that already existed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ParkingSessionStartConstraintViolationTest extends AbstractPostgresIT {
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

    @MockitoSpyBean
    private ParkingSessionRepository parkingSessionRepository;

    @Test
    void duplicateInsertBypassingThePreCheckSurfacesAs409NotFiveHundredAndLeavesExactlyOneRow() throws Exception {
        User owner = createUser("owner");
        Vehicle vehicle = registerVehicle(owner);
        Zone blueZone = zoneByName("Blue Zone");
        Tariff currentTariff = tariffRepository.findByZoneIdAndValidToIsNull(blueZone.getId()).orElseThrow();

        ParkingSession existingUnsettledSession = parkingSessionRepository.saveAndFlush(new ParkingSession()
                .setUserId(owner.getId())
                .setVehicleId(vehicle.getId())
                .setZoneId(blueZone.getId())
                .setTariffId(currentTariff.getId())
                .setStartedAt(Instant.now()));

        doReturn(Optional.empty()).when(parkingSessionRepository).findByVehicleIdAndPaidAtIsNull(vehicle.getId());

        mockMvc.perform(post("/api/v1/parking-sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartParkingSessionRequest(vehicle.getId(), blueZone.getId()))))
                .andExpect(status().isConflict());

        assertThat(parkingSessionRepository.findAll().stream()
                        .filter(session -> session.getVehicleId().equals(vehicle.getId()))
                        .toList())
                .containsExactly(existingUnsettledSession);
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
