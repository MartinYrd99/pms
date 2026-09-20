package com.pms.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Wires the symmetric key used to sign and verify access tokens.
 */
@Configuration
@Slf4j
public class JwtConfig {
    @Bean
    SecretKey jwtSigningKey(@Value("${pms.security.jwt.signing-key}") String signingKey) {
        byte[] keyBytes = signingKey.getBytes(StandardCharsets.UTF_8);

        log.info("Loaded a {}-byte {} JWT signing key", keyBytes.length, MacAlgorithm.HS256.getName());

        return new SecretKeySpec(keyBytes, MacAlgorithm.HS256.getName());
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        log.info("Configuring the {} JWT encoder", MacAlgorithm.HS256.getName());

        return NimbusJwtEncoder.withSecretKey(jwtSigningKey).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        log.info("Configuring the {} JWT decoder", MacAlgorithm.HS256.getName());

        return NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
