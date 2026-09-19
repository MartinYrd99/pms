package com.pms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class AuthRefreshTest extends AbstractPostgresIT {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void refreshWithValidTokenReturnsNewPairAndRevokesThePresentedToken() throws Exception {
        String username = "driver-" + UUID.randomUUID();
        LoginResponse loginResponse = registerAndLogin(username, "correct-horse-battery");
        User user = userRepository.findByUsername(username).orElseThrow();
        RefreshToken presented = findByRawToken(user, loginResponse.refreshToken());

        String responseBody = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(loginResponse.refreshToken()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        LoginResponse rotated = objectMapper.readValue(responseBody, LoginResponse.class);
        assertThat(rotated.accessToken()).isNotBlank().isNotEqualTo(loginResponse.accessToken());
        assertThat(rotated.refreshToken()).isNotBlank().isNotEqualTo(loginResponse.refreshToken());

        RefreshToken presentedAfterRotation = refreshTokenRepository.findById(presented.getId()).orElseThrow();
        assertThat(presentedAfterRotation.getRevokedAt()).isNotNull();

        RefreshToken rotatedRow = findByRawToken(user, rotated.refreshToken());
        assertThat(rotatedRow.getRevokedAt()).isNull();
        assertThat(rotatedRow.getExpiresAt()).isCloseTo(Instant.now().plus(Duration.ofHours(48)), within(1, ChronoUnit.MINUTES));

        List<RefreshToken> tokensForUser = refreshTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .toList();
        assertThat(tokensForUser).hasSize(2);
    }

    @Test
    void refreshingWithAnAlreadyUsedTokenReturns401OnTheSecondAttempt() throws Exception {
        String username = "driver-" + UUID.randomUUID();
        LoginResponse loginResponse = registerAndLogin(username, "correct-horse-battery");
        String rawRefreshToken = loginResponse.refreshToken();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(rawRefreshToken))))
                .andExpect(status().isOk());

        assertNeutralUnauthorized(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RefreshTokenRequest(rawRefreshToken))));
    }

    @Test
    void refreshingWithAnUnknownTokenReturns401WithTheNeutralBody() throws Exception {
        assertNeutralUnauthorized(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RefreshTokenRequest("not-a-known-token"))));
    }

    @Test
    void refreshingWithAnExpiredTokenReturns401WithTheSameNeutralBody() throws Exception {
        String username = "driver-" + UUID.randomUUID();
        registerUser(username, "correct-horse-battery");
        User user = userRepository.findByUsername(username).orElseThrow();

        String rawExpiredToken = "expired-" + UUID.randomUUID();
        RefreshToken expired = new RefreshToken()
                .setUser(user)
                .setTokenHash(sha256Hex(rawExpiredToken))
                .setExpiresAt(Instant.now().minus(Duration.ofHours(1)));
        refreshTokenRepository.save(expired);

        assertNeutralUnauthorized(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RefreshTokenRequest(rawExpiredToken))));

        RefreshToken stillExpired = refreshTokenRepository.findById(expired.getId()).orElseThrow();
        assertThat(stillExpired.getRevokedAt()).isNull();
    }

    private void assertNeutralUnauthorized(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("validation.unauthorized"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    private LoginResponse registerAndLogin(String username, String rawPassword) throws Exception {
        registerUser(username, rawPassword);

        String responseBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, rawPassword))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readValue(responseBody, LoginResponse.class);
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
