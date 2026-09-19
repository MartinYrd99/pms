package com.pms.zone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pms.AbstractPostgresIT;
import com.pms.auth.core.JwtService;
import com.pms.auth.core.User;
import com.pms.auth.core.UserRepository;
import com.pms.zone.core.RuleType;
import com.pms.zone.response.ZoneResponse;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ZoneCatalogTest extends AbstractPostgresIT {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Test
    void authenticatedUserSeesActiveZonesWithCurrentTariffAndNoInactiveZone() throws Exception {
        User user = createUser();

        String responseBody = mockMvc.perform(
                        get("/api/v1/zones").header(HttpHeaders.AUTHORIZATION, bearerTokenFor(user)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<ZoneResponse> zones = Arrays.asList(objectMapper.readValue(responseBody, ZoneResponse[].class));

        assertThat(zones).extracting(ZoneResponse::name).doesNotContain("Grey Zone");

        assertThat(zones)
                .filteredOn(zone -> zone.name().equals("Blue Zone"))
                .singleElement()
                .satisfies(blue -> {
                    assertThat(blue.city()).isEqualTo("Sofia");
                    assertThat(blue.hourlyRate()).isEqualByComparingTo(new BigDecimal("2.00"));
                    assertThat(blue.currency()).isEqualTo("EUR");
                    assertThat(blue.ruleType()).isEqualTo(RuleType.HOURLY);
                });

        assertThat(zones)
                .filteredOn(zone -> zone.name().equals("Green Zone"))
                .singleElement()
                .satisfies(green -> {
                    assertThat(green.city()).isEqualTo("Sofia");
                    assertThat(green.hourlyRate()).isEqualByComparingTo(new BigDecimal("1.00"));
                    assertThat(green.currency()).isEqualTo("EUR");
                    assertThat(green.ruleType()).isEqualTo(RuleType.HOURLY);
                });
    }

    @Test
    void missingBearerTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/zones")).andExpect(status().isUnauthorized());
    }

    private User createUser() {
        User user = new User()
                .setUsername("zone-catalog-" + UUID.randomUUID())
                .setPasswordHash(passwordEncoder.encode("irrelevant-password-1"));

        return userRepository.save(user);
    }

    private String bearerTokenFor(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }
}