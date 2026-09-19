package com.pms.error;

import static java.util.Objects.isNull;

import com.pms.parking.core.SessionAlreadyEndedException;
import com.pms.parking.core.UnsettledSessionException;
import com.pms.parking.response.ParkingSessionResponse;
import com.pms.parking.response.SessionAlreadyEndedResponse;
import com.pms.parking.response.SessionConflictResponse;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every exception the API can throw onto the single error contract
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String UNAUTHORIZED_CODE = "validation.unauthorized";
    private static final String FORBIDDEN_CODE = "validation.forbidden";
    private static final String NOT_FOUND_CODE = "validation.not-found";
    private static final String CONFLICT_CODE = "validation.conflict";
    private static final String REQUEST_INVALID_CODE = "validation.request-invalid";
    private static final String INTERNAL_ERROR_CODE = "error.internal";

    private final MessageSource messageSource;

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleBadRequest(IllegalArgumentException ex) {
        return resolveOrFallback(ex, REQUEST_INVALID_CODE);
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleConflict(IllegalStateException ex) {
        return resolveOrFallback(ex, CONFLICT_CODE);
    }

    @ExceptionHandler(UnsettledSessionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public SessionConflictResponse handleUnsettledSession(UnsettledSessionException ex) {
        ErrorResponse error = resolveOrFallback(ex, CONFLICT_CODE);

        return new SessionConflictResponse(error.code(), error.message(), ex.getBlockingSessionId());
    }

    @ExceptionHandler(SessionAlreadyEndedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public SessionAlreadyEndedResponse handleSessionAlreadyEnded(SessionAlreadyEndedException ex) {
        ErrorResponse error = resolveOrFallback(ex, CONFLICT_CODE);

        return new SessionAlreadyEndedResponse(error.code(), error.message(), ParkingSessionResponse.from(ex.getEndedSession()));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(EntityNotFoundException ex) {
        return resolveOrFallback(ex, NOT_FOUND_CODE);
    }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleForbidden(ForbiddenException ex) {
        return resolveOrFallback(ex, FORBIDDEN_CODE);
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleAccessDenied(AccessDeniedException ex) {
        return build(FORBIDDEN_CODE);
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleUnauthorized(AuthenticationException ex) {
        return build(UNAUTHORIZED_CODE);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(violation -> "%s %s".formatted(violation.getPropertyPath(), violation.getMessage()))
                .collect(Collectors.joining("; "));

        return buildWithDetail(REQUEST_INVALID_CODE, detail);
    }

    /**
     * Anything not mapped above never reaches the client as-is: it is logged in full here and
     * turned into a fixed, neutral error.
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);

        return build(INTERNAL_ERROR_CODE);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("Unhandled Spring MVC exception", ex);

            return new ResponseEntity<>(build(INTERNAL_ERROR_CODE), headers, statusCode);
        }

        ErrorResponse errorResponse = ex instanceof MethodArgumentNotValidException manve
                ? buildWithDetail(REQUEST_INVALID_CODE, fieldDetail(manve))
                : build(genericCodeFor(statusCode));

        return new ResponseEntity<>(errorResponse, headers, statusCode);
    }

    private String fieldDetail(MethodArgumentNotValidException ex) {
        return ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> "%s %s".formatted(fieldError.getField(), fieldError.getDefaultMessage()))
                .collect(Collectors.joining("; "));
    }

    private String genericCodeFor(HttpStatusCode statusCode) {
        return statusCode.value() == HttpStatus.NOT_FOUND.value() ? NOT_FOUND_CODE : REQUEST_INVALID_CODE;
    }

    /**
     * Domain exceptions carry the i18n key to resolve as their message. A key that does not
     * resolve — because it was mistyped, or because the exception is a JDK/library type whose
     * message was never meant to be a key — must never reach the client verbatim: the
     * raw exception is logged server-side and a fixed, generic code is returned instead.
     */
    private ErrorResponse resolveOrFallback(Exception ex, String fallbackCode) {
        String key = ex.getMessage();
        if (isNull(key)) {
            log.warn("{} thrown without a message; returning generic code '{}'", ex.getClass().getName(),
                    fallbackCode, ex);

            return build(fallbackCode);
        }
        try {
            return new ErrorResponse(key, messageSource.getMessage(key, null, LocaleContextHolder.getLocale()));
        } catch (NoSuchMessageException e) {
            log.warn("Unregistered error message key '{}' thrown by {}; returning generic code '{}'", key,
                    ex.getClass().getName(), fallbackCode, ex);

            return build(fallbackCode);
        }
    }

    private ErrorResponse build(String code) {
        return new ErrorResponse(code, resolve(code));
    }

    private ErrorResponse buildWithDetail(String code, String detail) {
        String message = isNull(detail) || detail.isBlank() ? resolve(code) : "%s: %s".formatted(resolve(code), detail);

        return new ErrorResponse(code, message);
    }

    private String resolve(String code) {
        return messageSource.getMessage(code, null, code, LocaleContextHolder.getLocale());
    }
}
