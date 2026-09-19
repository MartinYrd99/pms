package com.pms.auth.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {
    private static final String USERNAME_TAKEN_CODE = "validation.auth.username-taken";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

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
}
