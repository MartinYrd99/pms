package com.pms.auth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Proves time is injectable: a fixed {@link Clock} handed to the service is the exact instant
 * the stored expiry is derived from, with no tolerance window needed.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {
    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    void issueStampsExpiryFromTheInjectedClock() {
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        RefreshTokenService refreshTokenService = new RefreshTokenService(refreshTokenRepository, fixedClock);
        User user = new User().setId(1L).setUsername("driver-1");
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);

        refreshTokenService.issue(user);

        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getExpiresAt())
                .isEqualTo(FIXED_NOW.plus(RefreshTokenService.REFRESH_TOKEN_TTL));
    }
}
