package ar.edu.utn.frba.arbiter.auth.controllers;

import ar.edu.utn.frba.arbiter.auth.dto.LoginResponse;
import ar.edu.utn.frba.arbiter.auth.exceptions.AccountLockedException;
import ar.edu.utn.frba.arbiter.auth.exceptions.AuthExceptionHandler;
import ar.edu.utn.frba.arbiter.auth.exceptions.InvalidCredentialsException;
import ar.edu.utn.frba.arbiter.auth.services.AuthService;
import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import(AuthExceptionHandler.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    void login_validCredentials_returns200WithToken() throws Exception {
        LoginResponse response = new LoginResponse(
                "signed.jwt.token", Instant.now().plusSeconds(3600),
                "analista@arbiter.test", UserRole.ANALISTA_SINIESTROS, "Lucas", "Gómez");
        when(authService.login(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "analista@arbiter.test", "password": "changeme123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("signed.jwt.token"))
                .andExpect(jsonPath("$.rol").value("ANALISTA_SINIESTROS"));
    }

    @Test
    void login_invalidCredentials_returns401WithGenericMessage() throws Exception {
        when(authService.login(any())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "analista@arbiter.test", "password": "wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Email o contraseña inválidos"));
    }

    @Test
    void login_lockedAccount_returns423() throws Exception {
        when(authService.login(any())).thenThrow(new AccountLockedException(Instant.now().plusSeconds(900)));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "analista@arbiter.test", "password": "changeme123"}
                                """))
                .andExpect(status().isLocked());
    }

    @Test
    void login_blankPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "analista@arbiter.test", "password": ""}
                                """))
                .andExpect(status().isBadRequest());
    }
}
