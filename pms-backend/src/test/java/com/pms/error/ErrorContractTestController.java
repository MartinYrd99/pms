package com.pms.error;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Throwaway controller used only by {@link GlobalExceptionHandlerTest} to exercise every branch
 */
@RestController
@RequestMapping("/test/errors")
public class ErrorContractTestController {
    @GetMapping("/bad-request")
    void badRequest() {
        throw new IllegalArgumentException("validation.request-invalid");
    }

    @GetMapping("/conflict")
    void conflict() {
        throw new IllegalStateException("validation.test.distinct-conflict");
    }

    @GetMapping("/not-found")
    void notFound() {
        throw new EntityNotFoundException("validation.not-found");
    }

    @GetMapping("/forbidden")
    void forbidden() {
        throw new ForbiddenException("validation.forbidden");
    }

    @GetMapping("/access-denied")
    void accessDenied() {
        throw new AccessDeniedException("Access is denied");
    }

    @GetMapping("/unregistered-key")
    void unregisteredKey() {
        throw new IllegalArgumentException("No enum constant com.pms.parking.SessionStatus.FOO");
    }

    @GetMapping("/internal-mvc-error")
    void internalMvcError() {
        throw new HttpMessageNotWritableException("simulated response serialization failure");
    }

    @PostMapping("/validate")
    void validate(@Valid @RequestBody TestRequest request) {
        throw new IllegalArgumentException("validation.request-invalid");
    }

    @GetMapping("/boom")
    void boom() {
        throw new RuntimeException(
                "java.lang.RuntimeException at com.pms.error.ErrorContractTestController: "
                        + "SELECT * FROM users WHERE password = 'secret'");
    }

    public record TestRequest(@NotBlank String name) {
    }
}
