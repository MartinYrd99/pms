package com.pms.auth;

import static java.util.Objects.nonNull;

import com.pms.auth.core.AuthService;
import com.pms.auth.core.AuthTokens;
import com.pms.auth.core.token.RefreshCookieFactory;
import com.pms.auth.core.user.User;
import com.pms.auth.request.LoginRequest;
import com.pms.auth.request.RegisterRequest;
import com.pms.auth.response.AccessTokenResponse;
import com.pms.auth.response.RegisterResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Slf4j
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final RefreshCookieFactory refreshCookieFactory;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        log.info("POST /api/v1/auth/register for username '{}'", request.username());

        User user = authService.register(request.username(), request.password());

        log.info("Registered user {}", user.getId());

        return new RegisterResponse(user.getId(), user.getUsername());
    }

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.OK)
    public AccessTokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        log.info("POST /api/v1/auth/login for username '{}'", request.username());

        AuthTokens tokens = authService.login(request.username(), request.password());
        setRefreshCookie(response, refreshCookieFactory.issue(tokens.refreshToken()));

        return new AccessTokenResponse(tokens.accessToken());
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.OK)
    public AccessTokenResponse refresh(
            @CookieValue(name = RefreshCookieFactory.COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse response) {
        log.info("POST /api/v1/auth/refresh (cookie present={})", nonNull(refreshToken));

        try {
            AuthTokens tokens = authService.refresh(refreshToken);
            setRefreshCookie(response, refreshCookieFactory.issue(tokens.refreshToken()));

            return new AccessTokenResponse(tokens.accessToken());
        } catch (AuthenticationException e) {
            log.info("Refresh rejected; clearing the refresh cookie");

            setRefreshCookie(response, refreshCookieFactory.clear());
            throw e;
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @CookieValue(name = RefreshCookieFactory.COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse response) {
        log.info("POST /api/v1/auth/logout (cookie present={})", nonNull(refreshToken));

        authService.logout(refreshToken);

        setRefreshCookie(response, refreshCookieFactory.clear());
    }

    private void setRefreshCookie(HttpServletResponse response, ResponseCookie cookie) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
