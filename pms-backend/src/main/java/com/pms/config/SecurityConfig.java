package com.pms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Full authentication/authorization is wired in phase 2; this only carves out unauthenticated
 * exceptions for the actuator health and info probes so monitoring and Docker Compose can reach
 * them today, while every other request still falls back to Spring Security's secure-by-default
 * policy.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .anyRequest().authenticated());
        return http.build();
    }
}
