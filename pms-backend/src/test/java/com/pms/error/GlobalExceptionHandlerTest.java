package com.pms.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ErrorContractTestController.class)
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class MessageSourceConfig {

        @Bean
        MessageSource messageSource() {
            ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
            messageSource.setBasenames("messages", "test-messages");
            messageSource.setDefaultEncoding("UTF-8");
            return messageSource;
        }
    }

    @Test
    void illegalArgumentExceptionMapsTo400() throws Exception {
        mockMvc.perform(get("/test/errors/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation.request-invalid"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void illegalStateExceptionMapsTo409() throws Exception {
        mockMvc.perform(get("/test/errors/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("validation.test.distinct-conflict"))
                .andExpect(jsonPath("$.message")
                        .value("A distinct conflict message registered only for GlobalExceptionHandlerTest."))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void entityNotFoundExceptionMapsTo404() throws Exception {
        mockMvc.perform(get("/test/errors/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("validation.not-found"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void forbiddenExceptionMapsTo403() throws Exception {
        mockMvc.perform(get("/test/errors/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("validation.forbidden"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void accessDeniedExceptionMapsTo403() throws Exception {
        mockMvc.perform(get("/test/errors/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("validation.forbidden"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void unresolvedMessageKeyFallsBackToGenericCodeWithoutLeakingRawMessage() throws Exception {
        mockMvc.perform(get("/test/errors/unregistered-key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation.request-invalid"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.code", not(containsStringIgnoringCase("SessionStatus"))))
                .andExpect(jsonPath("$.message", not(containsStringIgnoringCase("SessionStatus"))))
                .andExpect(jsonPath("$.message", not(containsStringIgnoringCase("com.pms"))))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void unknownUrlMapsTo404() throws Exception {
        mockMvc.perform(get("/test/errors/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("validation.not-found"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void beanValidationFailureOnRequestBodyMapsTo400() throws Exception {
        mockMvc.perform(post("/test/errors/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation.request-invalid"))
                .andExpect(jsonPath("$.message", containsString("name")))
                .andExpect(jsonPath("$.message", containsString("must not be blank")))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void frameworkLevel5xxMapsToInternalErrorCode() throws Exception {
        mockMvc.perform(get("/test/errors/internal-mvc-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("error.internal"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void unmappedRuntimeExceptionMapsTo500WithoutLeakingInternals() throws Exception {
        mockMvc.perform(get("/test/errors/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("error.internal"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.message", not(containsStringIgnoringCase("RuntimeException"))))
                .andExpect(jsonPath("$.message", not(containsStringIgnoringCase("select"))))
                .andExpect(jsonPath("$.message", not(containsStringIgnoringCase("at com.pms"))))
                .andExpect(jsonPath("$.length()").value(2));
    }
}
