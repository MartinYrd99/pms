package com.pms.auth.core;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pms.auth.core.token.JwtService;
import com.pms.auth.core.token.RefreshTokenService;
import com.pms.auth.core.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Guards against a timing oracle in {@code login}: an unknown username must still run a BCrypt
 * comparison, not short-circuit, so it costs the same as a wrong password.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Test
    void loginWithUnknownUsernameStillRunsAPasswordComparisonAgainstADummyHash() {
        AuthService authService = new AuthService(userRepository, passwordEncoder, jwtService, refreshTokenService);
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> authService.login("ghost", "any-password"))
                .isInstanceOf(BadCredentialsException.class);

        verify(passwordEncoder).matches(anyString(), anyString());
    }
}
