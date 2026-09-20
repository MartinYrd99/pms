package com.pms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pms.AbstractPostgresIT;
import com.pms.auth.core.RefreshCookieFactory;
import com.pms.auth.core.RefreshToken;
import com.pms.auth.core.RefreshTokenRepository;
import com.pms.auth.core.User;
import com.pms.auth.core.UserRepository;
import com.pms.auth.request.LoginRequest;
import com.pms.auth.request.RegisterRequest;
import com.pms.auth.response.AccessTokenResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
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
    void refreshWithValidCookieReturnsNewAccessTokenAndRevokesThePresentedToken() throws Exception {
        String username = "driver-" + UUID.randomUUID();
        String rawRefreshToken = registerAndLogin(username, "correct-horse-battery");
        User user = userRepository.findByUsername(username).orElseThrow();
        RefreshToken presented = findByRawToken(user, rawRefreshToken);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshCookieFactory.COOKIE_NAME, rawRefreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        String rotatedRawToken = extractCookieValue(setCookie);
        assertThat(rotatedRawToken).isNotBlank().isNotEqualTo(rawRefreshToken);

        RefreshToken presentedAfterRotation = refreshTokenRepository.findById(presented.getId()).orElseThrow();
        assertThat(presentedAfterRotation.getRevokedAt()).isNotNull();

        RefreshToken rotatedRow = findByRawToken(user, rotatedRawToken);
        assertThat(rotatedRow.getRevokedAt()).isNull();

        List<RefreshToken> tokensForUser = refreshTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .toList();
        assertThat(tokensForUser).hasSize(2);
    }

    @Test
    void refreshingWithAnAlreadyUsedCookieReturns401OnTheSecondAttempt() throws Exception {
        String username = "driver-" + UUID.randomUUID();
        String rawRefreshToken = registerAndLogin(username, "correct-horse-battery");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshCookieFactory.COOKIE_NAME, rawRefreshToken)))
                .andExpect(status().isOk());

        assertNeutralUnauthorizedWithClearedCookie(
                post("/api/v1/auth/refresh").cookie(new Cookie(RefreshCookieFactory.COOKIE_NAME, rawRefreshToken)));
    }

    @Test
    void refreshingWithAnUnknownCookieReturns401WithTheNeutralBody() throws Exception {
        assertNeutralUnauthorizedWithClearedCookie(post("/api/v1/auth/refresh")
                .cookie(new Cookie(RefreshCookieFactory.COOKIE_NAME, "not-a-known-token")));
    }

    @Test
    void refreshingWithAnExpiredCookieReturns401WithTheSameNeutralBody() throws Exception {
        String username = "driver-" + UUID.randomUUID();
        registerUser(username, "correct-horse-battery");
        User user = userRepository.findByUsername(username).orElseThrow();

        String rawExpiredToken = "expired-" + UUID.randomUUID();
        RefreshToken expired = new RefreshToken()
                .setUser(user)
                .setTokenHash(sha256Hex(rawExpiredToken))
                .setExpiresAt(Instant.now().minus(Duration.ofHours(1)));
        refreshTokenRepository.save(expired);

        assertNeutralUnauthorizedWithClearedCookie(
                post("/api/v1/auth/refresh").cookie(new Cookie(RefreshCookieFactory.COOKIE_NAME, rawExpiredToken)));

        RefreshToken stillExpired = refreshTokenRepository.findById(expired.getId()).orElseThrow();
        assertThat(stillExpired.getRevokedAt()).isNull();
    }

    @Test
    void refreshingWithNoCookieAtAllReturns401WithTheSameNeutralBody() throws Exception {
        assertNeutralUnauthorizedWithClearedCookie(post("/api/v1/auth/refresh"));
    }

    private void assertNeutralUnauthorizedWithClearedCookie(MockHttpServletRequestBuilder request) throws Exception {
        ResultActions result = mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("validation.unauthorized"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));

        String setCookie = result.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("Max-Age=0");
        assertThat(setCookie).contains("Path=/api/v1/auth");
    }

    private String registerAndLogin(String username, String rawPassword) throws Exception {
        registerUser(username, rawPassword);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, rawPassword))))
                .andExpect(status().isOk())
                .andReturn();

        objectMapper.readValue(result.getResponse().getContentAsString(), AccessTokenResponse.class);

        return extractCookieValue(result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
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

    private String extractCookieValue(String setCookieHeader) {
        String prefix = RefreshCookieFactory.COOKIE_NAME + "=";
        String firstAttribute = setCookieHeader.split(";")[0].trim();

        return firstAttribute.substring(prefix.length());
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
