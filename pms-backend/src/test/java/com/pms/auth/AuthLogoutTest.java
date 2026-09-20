package com.pms.auth;

import static org.assertj.core.api.Assertions.assertThat;
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
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
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
    void logoutRevokesTheTokenClearsTheCookieAndASubsequentRefreshWithItReturns401() throws Exception {
        String username = "driver-" + UUID.randomUUID();
        registerUser(username, "correct-horse-battery");

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, "correct-horse-battery"))))
                .andExpect(status().isOk())
                .andReturn();
        String rawRefreshToken = extractCookieValue(loginResult.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        User user = userRepository.findByUsername(username).orElseThrow();

        MvcResult logoutResult = mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie(RefreshCookieFactory.COOKIE_NAME, rawRefreshToken)))
                .andExpect(status().isNoContent())
                .andExpect(jsonPath("$").doesNotExist())
                .andReturn();

        String setCookie = logoutResult.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("Max-Age=0");
        assertThat(setCookie).contains("Path=/api/v1/auth");

        RefreshToken loggedOut = findByRawToken(user, rawRefreshToken);
        assertThat(loggedOut.getRevokedAt()).isNotNull();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshCookieFactory.COOKIE_NAME, rawRefreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("validation.unauthorized"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void logoutWithAnUnknownCookieStillReturns204WithoutLeakingThat() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie(RefreshCookieFactory.COOKIE_NAME, "not-a-known-token")))
                .andExpect(status().isNoContent());
    }

    @Test
    void logoutWithNoCookieAtAllStillReturns204() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
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
