package com.pms.auth;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pms.auth.core.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerValidationTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    void blankUsernameReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"a-valid-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation.request-invalid"))
                .andExpect(jsonPath("$.length()").value(2));

        verifyNoInteractions(authService);
    }

    @Test
    void missingUsernameReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"a-valid-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation.request-invalid"))
                .andExpect(jsonPath("$.length()").value(2));

        verifyNoInteractions(authService);
    }

    @Test
    void blankPasswordReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a-valid-user\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation.request-invalid"))
                .andExpect(jsonPath("$.length()").value(2));

        verifyNoInteractions(authService);
    }

    @Test
    void missingPasswordReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a-valid-user\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation.request-invalid"))
                .andExpect(jsonPath("$.length()").value(2));

        verifyNoInteractions(authService);
    }
}
