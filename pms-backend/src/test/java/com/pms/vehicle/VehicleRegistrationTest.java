package com.pms.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pms.AbstractPostgresIT;
import com.pms.auth.core.token.JwtService;
import com.pms.auth.core.user.User;
import com.pms.auth.core.user.UserRepository;
import com.pms.vehicle.core.Vehicle;
import com.pms.vehicle.core.VehicleRepository;
import com.pms.vehicle.request.VehicleRequest;
import com.pms.vehicle.response.VehicleResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the two product rules of vehicle registration: the list is scoped to the caller, and a
 * plate belongs to exactly one account across the whole system.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VehicleRegistrationTest extends AbstractPostgresIT {
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

    @Test
    void registeringAVehicleScopesItToItsOwnerAndOwnerOnly() throws Exception {
        User owner = createUser("owner");
        User otherUser = createUser("other");
        String plate = uniquePlate();

        String responseBody = mockMvc.perform(post("/api/v1/vehicles")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VehicleRequest(plate, "Toyota", "Corolla"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.plate").value(plate))
                .andExpect(jsonPath("$.brand").value("Toyota"))
                .andExpect(jsonPath("$.model").value("Corolla"))
                .andReturn().getResponse().getContentAsString();

        VehicleResponse created = objectMapper.readValue(responseBody, VehicleResponse.class);
        Vehicle stored = vehicleRepository.findById(created.id()).orElseThrow();
        assertThat(stored.getUserId()).isEqualTo(owner.getId());

        mockMvc.perform(get("/api/v1/vehicles").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(created.id()))
                .andExpect(jsonPath("$[0].plate").value(plate));

        mockMvc.perform(get("/api/v1/vehicles").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(otherUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void registeringAPlateAlreadyOwnedByAnotherUserReturns409AndCreatesNoSecondRow() throws Exception {
        User firstOwner = createUser("first");
        User secondOwner = createUser("second");
        String plate = uniquePlate();

        mockMvc.perform(post("/api/v1/vehicles")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(firstOwner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VehicleRequest(plate, "Toyota", "Corolla"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/vehicles")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(secondOwner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VehicleRequest(plate, "Honda", "Civic"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("validation.vehicle.plate-taken"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));

        assertThat(vehicleRepository.findAll().stream().filter(vehicle -> vehicle.getPlate().equals(plate)).toList())
                .hasSize(1);
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

    private String uniquePlate() {
        return "PLATE-" + UUID.randomUUID();
    }
}
