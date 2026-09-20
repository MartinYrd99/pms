package com.pms.auth.core;

import static java.util.Objects.isNull;

import com.pms.auth.core.token.JwtService;
import com.pms.auth.core.token.RefreshTokenService;
import com.pms.auth.core.user.User;
import com.pms.auth.core.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {
    private static final String USERNAME_TAKEN_CODE = "validation.auth.username-taken";

    /**
     * A syntactically valid BCrypt hash that matches no real password. Verified against on every
     * login where the username is unknown, so an unknown username still pays the same BCrypt cost
     * as a wrong password
     */
    private static final String DUMMY_PASSWORD_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public User register(String username, String rawPassword) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalStateException(USERNAME_TAKEN_CODE);
        }

        User user = new User()
                .setUsername(username)
                .setPasswordHash(passwordEncoder.encode(rawPassword));

        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException(USERNAME_TAKEN_CODE);
        }
    }

    /**
     * An unknown username and a wrong password fail identically: neither the response nor the
     * time taken to produce it reveals which of the two was the problem. A BCrypt verification
     * always runs — against the real hash when the user exists, against a fixed dummy hash when
     * it does not — so both cases pay the same cost before throwing the same exception.
     */
    public AuthTokens login(String username, String rawPassword) {
        User user = userRepository.findByUsername(username).orElse(null);
        String passwordHash = isNull(user) ? DUMMY_PASSWORD_HASH : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(rawPassword, passwordHash);

        if (isNull(user) || !passwordMatches) {
            throw new BadCredentialsException("Invalid username or password");
        }

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = refreshTokenService.issue(user);

        return new AuthTokens(accessToken, refreshToken);
    }

    /**
     * Rotation: the presented refresh token is revoked and a brand-new access/refresh pair is
     * issued in the same transaction, so a token is never both revoked and un-replaced. A missing
     * cookie is treated exactly like an unknown one.
     */
    @Transactional
    public AuthTokens refresh(String rawRefreshToken) {
        if (isNull(rawRefreshToken)) {
            throw new BadCredentialsException("Invalid or expired refresh token");
        }

        User user = refreshTokenService.rotate(rawRefreshToken);
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = refreshTokenService.issue(user);

        return new AuthTokens(accessToken, refreshToken);
    }

    /**
     * A missing cookie is a no-op, same as an already-invalid token: logout never reveals whether
     * a token was presented at all.
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        if (isNull(rawRefreshToken)) {
            return;
        }

        refreshTokenService.revoke(rawRefreshToken);
    }
}
