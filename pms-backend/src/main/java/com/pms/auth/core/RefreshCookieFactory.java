package com.pms.auth.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshCookieFactory {
    public static final String COOKIE_NAME = "refreshToken";

    private static final String COOKIE_PATH = "/api/v1/auth";
    private static final String SAME_SITE = "Strict";

    private final boolean secure;

    public RefreshCookieFactory(@Value("${pms.auth.refresh-cookie.secure}") boolean secure) {
        this.secure = secure;
    }

    public ResponseCookie issue(String rawRefreshToken) {
        return baseCookie(rawRefreshToken)
                .maxAge(RefreshTokenService.REFRESH_TOKEN_TTL)
                .build();
    }

    public ResponseCookie clear() {
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