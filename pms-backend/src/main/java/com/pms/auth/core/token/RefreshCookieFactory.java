package com.pms.auth.core.token;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RefreshCookieFactory {
    public static final String COOKIE_NAME = "refreshToken";

    private static final String COOKIE_PATH = "/api/v1/auth";
    private static final String SAME_SITE = "Strict";

    private final boolean secure;

    public RefreshCookieFactory(@Value("${pms.auth.refresh-cookie.secure}") boolean secure) {
        this.secure = secure;

        log.info("Refresh cookies are issued HttpOnly, SameSite={}, Path={}, Secure={}", SAME_SITE, COOKIE_PATH, secure);
    }

    public ResponseCookie issue(String rawRefreshToken) {
        log.info("Issuing a refresh cookie valid for {}", RefreshTokenService.REFRESH_TOKEN_TTL);

        return baseCookie(rawRefreshToken)
                .maxAge(RefreshTokenService.REFRESH_TOKEN_TTL)
                .build();
    }

    public ResponseCookie clear() {
        log.info("Clearing the refresh cookie");

        return baseCookie("")
                .maxAge(0)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(SAME_SITE)
                .path(COOKIE_PATH);
    }
}
