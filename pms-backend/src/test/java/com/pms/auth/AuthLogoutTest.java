package com.pms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pms.AbstractPostgresIT;
import com.pms.auth.core.RefreshToken;
import com.pms.auth.core.RefreshTokenRepository;
import com.pms.auth.core.User;
import com.pms.auth.core.UserRepository;
import com.pms.auth.request.LoginRequest;
import com.pms.auth.request.RefreshTokenRequest;
import com.pms.auth.request.RegisterRequest;
import com.pms.auth.response.LoginResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class AuthLogoutTest extends AbstractPostgresIT {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void logoutRevokesTheTokenAndASubsequentRefreshWithItReturns401() throws Exception {
        String username = "driver-" + UUID.randomUUID();
        registerUser(username, "correct-horse-battery");

        String loginResponseBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, "correct-horse-battery"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        LoginResponse loginResponse = objectMapper.readValue(loginResponseBody, LoginResponse.class);
        User user = userRepository.findByUsername(username).orElseThrow();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(loginResponse.refreshToken()))))
                .andExpect(status().isNoContent())
                .andExpect(jsonPath("$").doesNotExist());

        RefreshToken loggedOut = findByRawToken(user, loginResponse.refreshToken());
        assertThat(loggedOut.getRevokedAt()).isNotNull();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(loginResponse.refreshToken()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("validation.unauthorized"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void logoutWithAnUnknownTokenStillReturns204WithoutLeakingThat() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest("not-a-known-token"))))
                .andExpect(status().isNoContent());
    }

    private void registerUser(String username, String rawPassword) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(username, rawPassword))))
                .andExpect(status().isCreated());
    }

    private RefreshToken findByRawToken(User user, String rawToken) {
        String tokenHash = sha256Hex(rawToken);

        return refreshTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .filter(token -> token.getTokenHash().equals(tokenHash))
                .findFirst()
                .orElseThrow();
    }

    private String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
