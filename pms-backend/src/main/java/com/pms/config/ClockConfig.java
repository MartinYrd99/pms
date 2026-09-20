package com.pms.config;

import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class ClockConfig {
    @Bean
    Clock clock() {
        log.info("Using the system UTC clock as the application clock");

        return Clock.systemUTC();
    }
}
