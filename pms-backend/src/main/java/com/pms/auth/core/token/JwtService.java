package com.pms.auth.core.token;

import com.pms.auth.core.user.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues short-lived access tokens. The user id is carried as the JWT subject, the sole source of
 * identity the authenticated filter chain trusts.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class JwtService {
    public static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(10);

    private final JwtEncoder jwtEncoder;
    private final Clock clock;

    public String generateAccessToken(User user) {
        Instant issuedAt = Instant.now(clock);
        Instant expiresAt = issuedAt.plus(ACCESS_TOKEN_TTL);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();

        log.info("Issuing an access token for user {} expiring at {}", user.getId(), expiresAt);

        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
