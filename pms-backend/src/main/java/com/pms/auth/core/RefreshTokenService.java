package com.pms.auth.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Issues opaque refresh tokens. The raw value is handed to the client once and never stored; only
 * its SHA-256 hash is persisted, so a database leak yields nothing usable.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    public static final Duration REFRESH_TOKEN_TTL = Duration.ofHours(48);

    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final String HASH_ALGORITHM = "SHA-256";

    private final RefreshTokenRepository refreshTokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public String issue(User user) {
        String rawToken = generateRawToken();
        Instant now = Instant.now();

        RefreshToken refreshToken = new RefreshToken()
                .setUser(user)
                .setTokenHash(hash(rawToken))
                .setExpiresAt(now.plus(REFRESH_TOKEN_TTL));
        refreshTokenRepository.save(refreshToken);

        return rawToken;
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);

            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
