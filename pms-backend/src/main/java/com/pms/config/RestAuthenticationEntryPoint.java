package com.pms.config;

import com.pms.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * A request rejected inside the security filter chain — missing, malformed or expired token —
 * never reaches {@code GlobalExceptionHandler}, so this produces the same {@code {code, message}}
 * contract directly.
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private static final String UNAUTHORIZED_CODE = "validation.unauthorized";

    private final MessageSource messageSource;
    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        String message = messageSource.getMessage(UNAUTHORIZED_CODE, null, UNAUTHORIZED_CODE, LocaleContextHolder.getLocale());
        objectMapper.writeValue(response.getWriter(), new ErrorResponse(UNAUTHORIZED_CODE, message));
    }
}
