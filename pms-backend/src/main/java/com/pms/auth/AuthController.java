package com.pms.auth;

import com.pms.auth.core.AuthService;
import com.pms.auth.core.AuthTokens;
import com.pms.auth.core.User;
import com.pms.auth.request.LoginRequest;
import com.pms.auth.request.RefreshTokenRequest;
import com.pms.auth.request.RegisterRequest;
import com.pms.auth.response.LoginResponse;
import com.pms.auth.response.RegisterResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        User user = authService.register(request.username(), request.password());

        return new RegisterResponse(user.getId(), user.getUsername());
    }

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.OK)
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        AuthTokens tokens = authService.login(request.username(), request.password());

        return new LoginResponse(tokens.accessToken(), tokens.refreshToken());
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.OK)
    public LoginResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        AuthTokens tokens = authService.refresh(request.refreshToken());

        return new LoginResponse(tokens.accessToken(), tokens.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.refreshToken());
    }
}
