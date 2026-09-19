package com.pms.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pms.AbstractPostgresIT;
import com.pms.auth.request.RegisterRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the filter chain itself: authenticated paths reject a missing or garbage bearer token
 * with the same {@code {code, message}} contract as the rest of the API, while the public auth
 * endpoints stay reachable without one.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityFilterChainTest extends AbstractPostgresIT {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void authenticatedPathWithNoTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/vehicles"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("validation.unauthorized"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void authenticatedPathWithGarbageTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/vehicles").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("validation.unauthorized"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void swaggerUiIsReachableWithoutATokenWhileAuthenticatedPathsStillReject() throws Exception {
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/api/v1/vehicles")).andExpect(status().isUnauthorized());
    }

    @Test
    void registerStaysReachableWithoutAToken() throws Exception {
        String username = "driver-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(username, "a-valid-password"))))
                .andExpect(status().isCreated());
    }
}
