package com.pms.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class OpenApiConfig {
    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    @Bean
    OpenAPI pmsOpenApi() {
        log.info("Publishing the PMS v1 OpenAPI document with the '{}' security scheme", BEARER_SCHEME_NAME);

        return new OpenAPI()
                .info(new Info()
                        .title("PMS API")
                        .version("v1")
                        .description("Parking Management System API"))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME_NAME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }
}
