package com.pms.config;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Verifies the bearer access token on every request and, when valid, puts the token subject —
 * the user id — into the security context as the principal. A missing header leaves the request
 * anonymous so the authorization rule (and the entry point) decide the outcome; a present but
 * invalid token is rejected outright, since it can never turn into a valid one further down the
 * chain.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (nonNull(header) && header.startsWith(BEARER_PREFIX)) {
            try {
                Jwt jwt = jwtDecoder.decode(header.substring(BEARER_PREFIX.length()));
                String subject = jwt.getSubject();

                if (isNull(subject)) {
                    throw new BadJwtException("Access token carries no subject");
                }

                SecurityContextHolder.getContext()
                        .setAuthentication(
                                new UsernamePasswordAuthenticationToken(
                                        Long.valueOf(subject), null, List.of()
                                )
                        );
            } catch (JwtException | NumberFormatException e) {
                log.debug("Rejecting request with invalid bearer token", e);

                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
