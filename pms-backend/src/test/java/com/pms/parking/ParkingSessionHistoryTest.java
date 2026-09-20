package com.pms.parking;

import static org.hamcrest.Matchers.empty;
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
import com.pms.vehicle.core.Vehicle;
import com.pms.vehicle.core.VehicleRepository;
import com.pms.zone.core.Zone;
import com.pms.zone.core.ZoneRepository;
import com.pms.zone.core.tariff.Tariff;
import com.pms.zone.core.tariff.TariffRepository;
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
 * Verifies the paginated history endpoint: newest-first ordering, page slicing without overlap,
 * strict per-user isolation, the same field shapes as the detail endpoint, and that an oversized
 * page size is clamped rather than honoured.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ParkingSessionHistoryTest extends AbstractPostgresIT {
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
    void ordersNewestFirstAndSlicesPagesWithoutOverlap() throws Exception {
        User owner = createUser("owner");
        Zone blueZone = zoneByName("Blue Zone");
        Tariff blueTariff = tariffRepository.findByZoneIdAndValidToIsNull(blueZone.getId()).orElseThrow();

        ParkingSession oldest = saveSession(owner, registerVehicle(owner), blueZone, blueTariff, FIXED_NOW.minusSeconds(4 * 3_600), null, null);
        ParkingSession second = saveSession(owner, registerVehicle(owner), blueZone, blueTariff, FIXED_NOW.minusSeconds(3 * 3_600), null, null);
        ParkingSession third = saveSession(owner, registerVehicle(owner), blueZone, blueTariff, FIXED_NOW.minusSeconds(2 * 3_600), null, null);
        ParkingSession newest = saveSession(owner, registerVehicle(owner), blueZone, blueTariff, FIXED_NOW.minusSeconds(3_600), null, null);

        mockMvc.perform(get("/api/v1/parking-sessions")
                        .param("page", "0")
                        .param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(newest.getId()))
                .andExpect(jsonPath("$.content[1].id").value(third.getId()));

        mockMvc.perform(get("/api/v1/parking-sessions")
                        .param("page", "1")
                        .param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(second.getId()))
                .andExpect(jsonPath("$.content[1].id").value(oldest.getId()));
    }

    @Test
    void anotherUsersSessionsNeverAppearAndAUserWithNoSessionsGetsAnEmptyPage() throws Exception {
        User owner = createUser("owner");
        Vehicle ownerVehicle = registerVehicle(owner);
        Zone blueZone = zoneByName("Blue Zone");
        Tariff blueTariff = tariffRepository.findByZoneIdAndValidToIsNull(blueZone.getId()).orElseThrow();

        ParkingSession ownerSession = saveSession(owner, ownerVehicle, blueZone, blueTariff, FIXED_NOW.minusSeconds(3_600), null, null);

        User otherUser = createUser("other");
        Vehicle otherVehicle = registerVehicle(otherUser);
        saveSession(otherUser, otherVehicle, blueZone, blueTariff, FIXED_NOW.minusSeconds(1_800), null, null);

        mockMvc.perform(get("/api/v1/parking-sessions").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ownerSession.getId()));

        User userWithNoSessions = createUser("lonely");

        mockMvc.perform(get("/api/v1/parking-sessions").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(userWithNoSessions)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").value(empty()));
    }

    @Test
    void endedSessionCarriesItsStoredAmountWhileAnActiveOneReportsThemAsNull() throws Exception {
        User owner = createUser("owner");
        Zone blueZone = zoneByName("Blue Zone");
        Tariff blueTariff = tariffRepository.findByZoneIdAndValidToIsNull(blueZone.getId()).orElseThrow();

        Instant startedAt = FIXED_NOW.minusSeconds(7_200);
        Instant endedAt = FIXED_NOW.minusSeconds(3_600);
        ParkingSession ended = saveSession(owner, registerVehicle(owner), blueZone, blueTariff, startedAt, endedAt, new BigDecimal("4.00"));
        ParkingSession active = saveSession(owner, registerVehicle(owner), blueZone, blueTariff, FIXED_NOW.minusSeconds(600), null, null);

        mockMvc.perform(get("/api/v1/parking-sessions").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(active.getId()))
                .andExpect(jsonPath("$.content[0].endedAt").hasJsonPath())
                .andExpect(jsonPath("$.content[0].endedAt").value(nullValue()))
                .andExpect(jsonPath("$.content[0].amount").hasJsonPath())
                .andExpect(jsonPath("$.content[0].amount").value(nullValue()))
                .andExpect(jsonPath("$.content[0].paymentStatus").value(nullValue()))
                .andExpect(jsonPath("$.content[1].id").value(ended.getId()))
                .andExpect(jsonPath("$.content[1].endedAt").value(endedAt.toString()))
                .andExpect(jsonPath("$.content[1].amount").value(4.00))
                .andExpect(jsonPath("$.content[1].paymentStatus").value(nullValue()));
    }

    @Test
    void aSizeAboveTheCapIsClampedRatherThanHonoured() throws Exception {
        User owner = createUser("owner");
        Zone blueZone = zoneByName("Blue Zone");
        Tariff blueTariff = tariffRepository.findByZoneIdAndValidToIsNull(blueZone.getId()).orElseThrow();

        saveSession(owner, registerVehicle(owner), blueZone, blueTariff, FIXED_NOW.minusSeconds(3_600), null, null);
        saveSession(owner, registerVehicle(owner), blueZone, blueTariff, FIXED_NOW.minusSeconds(1_800), null, null);

        mockMvc.perform(get("/api/v1/parking-sessions")
                        .param("size", "100000")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    private ParkingSession saveSession(
            User owner, Vehicle vehicle, Zone zone, Tariff tariff, Instant startedAt, Instant endedAt, BigDecimal amount) {
        return parkingSessionRepository.save(new ParkingSession()
                .setUserId(owner.getId())
                .setVehicleId(vehicle.getId())
                .setZoneId(zone.getId())
                .setTariffId(tariff.getId())
                .setStartedAt(startedAt)
                .setEndedAt(endedAt)
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