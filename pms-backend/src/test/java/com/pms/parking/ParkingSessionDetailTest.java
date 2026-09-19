package com.pms.parking;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pms.AbstractPostgresIT;
import com.pms.auth.core.JwtService;
import com.pms.auth.core.User;
import com.pms.auth.core.UserRepository;
import com.pms.parking.core.ParkingSession;
import com.pms.parking.core.ParkingSessionRepository;
import com.pms.vehicle.core.Vehicle;
import com.pms.vehicle.core.VehicleRepository;
import com.pms.zone.core.Tariff;
import com.pms.zone.core.TariffRepository;
import com.pms.zone.core.Zone;
import com.pms.zone.core.ZoneRepository;
import java.math.BigDecimal;
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
 * Verifies the session-detail endpoint: it reports the vehicle/zone/times/amount of the owner's own
 * session, refuses another user's session without leaking anything about it, answers 404 for an
 * unknown id, and stays behind authentication.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ParkingSessionDetailTest extends AbstractPostgresIT {
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

    @Test
    void activeSessionReturns200WithNullEndedAtAmountAndPaymentStatus() throws Exception {
        User owner = createUser("owner");
        Vehicle vehicle = registerVehicle(owner);
        Zone blueZone = zoneByName("Blue Zone");
        Tariff blueTariff = tariffRepository.findByZoneIdAndValidToIsNull(blueZone.getId()).orElseThrow();

        ParkingSession session = startSession(owner, vehicle, blueZone, blueTariff, FIXED_NOW);

        mockMvc.perform(get("/api/v1/parking-sessions/{id}", session.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(session.getId()))
                .andExpect(jsonPath("$.vehicle.id").value(vehicle.getId()))
                .andExpect(jsonPath("$.vehicle.plate").value(vehicle.getPlate()))
                .andExpect(jsonPath("$.vehicle.brand").value(vehicle.getBrand()))
                .andExpect(jsonPath("$.vehicle.model").value(vehicle.getModel()))
                .andExpect(jsonPath("$.zone.id").value(blueZone.getId()))
                .andExpect(jsonPath("$.zone.name").value(blueZone.getName()))
                .andExpect(jsonPath("$.zone.city").value(blueZone.getCity()))
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.endedAt").hasJsonPath())
                .andExpect(jsonPath("$.endedAt").value(nullValue()))
                .andExpect(jsonPath("$.amount").hasJsonPath())
                .andExpect(jsonPath("$.amount").value(nullValue()))
                .andExpect(jsonPath("$.paymentStatus").hasJsonPath())
                .andExpect(jsonPath("$.paymentStatus").value(nullValue()));
    }

    @Test
    void endedSessionReturnsItsStoredEndedAtAndAmountWithPaymentStatusStillNull() throws Exception {
        User owner = createUser("owner");
        Vehicle vehicle = registerVehicle(owner);
        Zone blueZone = zoneByName("Blue Zone");
        Tariff blueTariff = tariffRepository.findByZoneIdAndValidToIsNull(blueZone.getId()).orElseThrow();

        Instant endedAt = FIXED_NOW.plusSeconds(3_600);
        ParkingSession session = parkingSessionRepository.save(new ParkingSession()
                .setUserId(owner.getId())
                .setVehicleId(vehicle.getId())
                .setZoneId(blueZone.getId())
                .setTariffId(blueTariff.getId())
                .setStartedAt(FIXED_NOW)
                .setEndedAt(endedAt)
                .setAmount(new BigDecimal("2.00")));

        mockMvc.perform(get("/api/v1/parking-sessions/{id}", session.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(session.getId()))
                .andExpect(jsonPath("$.endedAt").value(endedAt.toString()))
                .andExpect(jsonPath("$.amount").value(2.00))
                .andExpect(jsonPath("$.paymentStatus").hasJsonPath())
                .andExpect(jsonPath("$.paymentStatus").value(nullValue()));
    }

    @Test
    void anotherUsersSessionReturns403WithoutLeakingAnyDetail() throws Exception {
        User owner = createUser("owner");
        User requester = createUser("requester");
        Vehicle vehicle = registerVehicle(owner);
        Zone blueZone = zoneByName("Blue Zone");
        Tariff blueTariff = tariffRepository.findByZoneIdAndValidToIsNull(blueZone.getId()).orElseThrow();

        ParkingSession session = startSession(owner, vehicle, blueZone, blueTariff, FIXED_NOW);

        mockMvc.perform(get("/api/v1/parking-sessions/{id}", session.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(requester)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("validation.parking-session.not-owned"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void unknownSessionReturns404() throws Exception {
        User owner = createUser("owner");

        mockMvc.perform(get("/api/v1/parking-sessions/{id}", Long.MAX_VALUE)
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("validation.parking-session.not-found"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void missingBearerTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/parking-sessions/{id}", 1L)).andExpect(status().isUnauthorized());
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
