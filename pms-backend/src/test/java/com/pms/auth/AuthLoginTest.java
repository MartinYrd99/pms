package com.pms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pms.AbstractPostgresIT;
import com.pms.auth.core.token.RefreshCookieFactory;
import com.pms.auth.core.token.RefreshToken;
import com.pms.auth.core.token.RefreshTokenRepository;
import com.pms.auth.core.user.User;
import com.pms.auth.core.user.UserRepository;
import com.pms.auth.request.LoginRequest;
import com.pms.auth.request.RegisterRequest;
import com.pms.auth.response.AccessTokenResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class AuthLoginTest extends AbstractPostgresIT {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void loginWithCorrectCredentialsReturnsOnlyTheAccessTokenAndPersistsHashedRefreshToken() throws Exception {
        String username = "driver-" + UUID.randomUUID();
        String rawPassword = "correct-horse-battery";
        registerUser(username, rawPassword);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, rawPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn();

        AccessTokenResponse loginResponse = objectMapper.readValue(result.getResponse().getContentAsString(), AccessTokenResponse.class);
        assertThat(loginResponse.accessToken()).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("refreshToken");

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains(RefreshCookieFactory.COOKIE_NAME + "=");
        assertThat(setCookie).containsIgnoringCase("HttpOnly");
        assertThat(setCookie).contains("SameSite=Strict");
        assertThat(setCookie).contains("Path=/api/v1/auth");
        assertThat(maxAgeSecondsOf(setCookie)).isCloseTo(Duration.ofHours(48).toSeconds(), within(60L));

        User user = userRepository.findByUsername(username).orElseThrow();
        List<RefreshToken> tokens = refreshTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .toList();
        assertThat(tokens).hasSize(1);

        RefreshToken persisted = tokens.get(0);
        assertThat(persisted.getRevokedAt()).isNull();
        assertThat(persisted.getExpiresAt()).isCloseTo(Instant.now().plus(Duration.ofHours(48)), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void loginWithWrongPasswordReturns401WithNeutralBody() throws Exception {
        String username = "driver-" + UUID.randomUUID();
        registerUser(username, "the-real-password");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, "the-wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("validation.unauthorized"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void loginWithUnknownUsernameReturnsIdentical401Body() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("driver-" + UUID.randomUUID(), "any-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("validation.unauthorized"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    private void registerUser(String username, String rawPassword) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(username, rawPassword))))
                .andExpect(status().isCreated());
    }

    private long maxAgeSecondsOf(String setCookieHeader) {
        return Stream.of(setCookieHeader.split(";"))
                .map(String::trim)
                .filter(attribute -> attribute.regionMatches(true, 0, "Max-Age=", 0, "Max-Age=".length()))
                .map(attribute -> attribute.substring("Max-Age=".length()))
                .mapToLong(Long::parseLong)
                .findFirst()
                .orElseThrow();
    }
}
