package com.pms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pms.AbstractPostgresIT;
import com.pms.auth.core.User;
import com.pms.auth.core.UserRepository;
import com.pms.auth.request.RegisterRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class AuthRegistrationTest extends AbstractPostgresIT {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registeringWithFreshUsernameCreatesUserWithBCryptHash() throws Exception {
        String username = "driver-" + UUID.randomUUID();
        String rawPassword = "correct-horse-battery";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(username, rawPassword))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        List<User> matches = usersNamed(username);
        assertThat(matches).hasSize(1);

        String storedHash = matches.get(0).getPasswordHash();
        assertThat(storedHash).isNotEqualTo(rawPassword);
        assertThat(passwordEncoder.matches(rawPassword, storedHash)).isTrue();
    }

    @Test
    void registeringAnAlreadyTakenUsernameReturns409AndCreatesNoSecondRow() throws Exception {
        String username = "driver-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(username, "first-password-1"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(username, "second-password-2"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("validation.auth.username-taken"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));

        assertThat(usersNamed(username)).hasSize(1);
    }

    private List<User> usersNamed(String username) {
        return userRepository.findAll().stream().filter(user -> user.getUsername().equals(username)).toList();
    }
}
