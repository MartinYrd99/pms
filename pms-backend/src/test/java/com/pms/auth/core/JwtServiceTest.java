package com.pms.auth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.assertj.core.data.TemporalUnitOffset;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Verifies the access token JwtService issues, independent of the Spring context: its expiry is
 * exactly 10 minutes after issue and it only verifies against the key it was signed with.
 */
class JwtServiceTest {
    private static final SecretKey SIGNING_KEY =
            new SecretKeySpec("unit-test-jwt-signing-key-at-least-32-bytes-long".getBytes(StandardCharsets.UTF_8),
                    MacAlgorithm.HS256.getName());

    @Test
    void issuedAccessTokenExpiresExactlyTenMinutesAfterIssueAndVerifiesWithConfiguredKey() {
        JwtEncoder jwtEncoder = NimbusJwtEncoder.withSecretKey(SIGNING_KEY).algorithm(MacAlgorithm.HS256).build();
        JwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(SIGNING_KEY).macAlgorithm(MacAlgorithm.HS256).build();
        JwtService jwtService = new JwtService(jwtEncoder);
        User user = new User().setId(42L).setUsername("driver-42");

        String token = jwtService.generateAccessToken(user);

        Jwt decoded = jwtDecoder.decode(token);
        assertThat(decoded.getSubject()).isEqualTo("42");
        assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt())).isEqualTo(Duration.ofMinutes(10));
        assertThat(decoded.getExpiresAt()).isCloseTo(Instant.now().plus(Duration.ofMinutes(10)), withinTwoSeconds());
    }

    @Test
    void tokenSignedWithADifferentKeyFailsVerification() {
        JwtEncoder jwtEncoder = NimbusJwtEncoder.withSecretKey(SIGNING_KEY).algorithm(MacAlgorithm.HS256).build();
        JwtService jwtService = new JwtService(jwtEncoder);
        User user = new User().setId(7L).setUsername("driver-7");
        String token = jwtService.generateAccessToken(user);

        SecretKey otherKey = new SecretKeySpec(
                "a-completely-different-unit-test-signing-key-value".getBytes(StandardCharsets.UTF_8),
                MacAlgorithm.HS256.getName());
        JwtDecoder decoderWithWrongKey =
                NimbusJwtDecoder.withSecretKey(otherKey).macAlgorithm(MacAlgorithm.HS256).build();

        assertThatThrownBy(() -> decoderWithWrongKey.decode(token)).isInstanceOf(JwtException.class);
    }

    /**
     * JWT numeric-date claims are second-precision, so a truncated expiry can legitimately land up
     * to a second earlier than the millisecond-precision instant computed just before it.
     */
    private static TemporalUnitOffset withinTwoSeconds() {
        return within(2, ChronoUnit.SECONDS);
    }
}
