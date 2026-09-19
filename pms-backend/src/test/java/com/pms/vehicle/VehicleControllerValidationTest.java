package com.pms.vehicle;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pms.AbstractPostgresIT;
import com.pms.auth.core.JwtService;
import com.pms.auth.core.User;
import com.pms.auth.core.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the global error contract on the vehicles endpoints: a validation failure produces the
 * standard {@code {code, message}} 400, and a missing bearer token is rejected before validation
 * even runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VehicleControllerValidationTest extends AbstractPostgresIT {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Test
    void blankPlateReturns400() throws Exception {
        User user = createUser();

        mockMvc.perform(post("/api/v1/vehicles")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plate\":\"\",\"brand\":\"Toyota\",\"model\":\"Corolla\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation.request-invalid"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void missingBearerTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plate\":\"AA-000-BB\",\"brand\":\"Toyota\",\"model\":\"Corolla\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("validation.unauthorized"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    private User createUser() {
        User user = new User()
                .setUsername("validation-user-" + UUID.randomUUID())
                .setPasswordHash(passwordEncoder.encode("irrelevant-password-1"));

        return userRepository.save(user);
    }

    private String bearerTokenFor(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }
}
