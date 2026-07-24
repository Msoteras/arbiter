package ar.edu.utn.frba.arbiter.auth.controllers;

import ar.edu.utn.frba.arbiter.auth.models.entities.User;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.auth.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H0002 - Alta de Usuarios. Usa /api/v1/auth/login real (no arma tokens a mano) para no
 * duplicar la lógica de emisión — auth-service es el que emite, a diferencia de cases-service.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest extends AbstractPersistenceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Long referenteId;
    private Long analistaId;
    private Long pendienteId;

    @BeforeEach
    void seedUsers() {
        userRepository.deleteAll();

        referenteId = userRepository.save(User.builder()
                .email("referente.test@gmail.com")
                .passwordHash(passwordEncoder.encode("changeme123"))
                .nombre("Sofía")
                .apellido("Martínez")
                .rol(UserRole.REFERENTE_ASEGURADORA)
                .activated(true)
                .build()).getId();

        analistaId = userRepository.save(User.builder()
                .email("analista.test@gmail.com")
                .passwordHash(passwordEncoder.encode("changeme123"))
                .nombre("Lucas")
                .apellido("Gómez")
                .rol(UserRole.ANALISTA_SINIESTROS)
                .activated(true)
                .build()).getId();

        pendienteId = userRepository.save(User.builder()
                .email("pendiente.test@gmail.com")
                .passwordHash(passwordEncoder.encode("no-usable"))
                .nombre("Martina")
                .apellido("Soteras")
                .rol(UserRole.ANALISTA_SINIESTROS)
                .inviteToken("tok-viejo")
                .inviteExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build()).getId();
    }

    private String tokenFor(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "changeme123"}
                                """.formatted(email)))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        return node.get("token").asText();
    }

    @Test
    void createUser_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAnalistaBody("primera.vez@gmail.com")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createUser_asAnalista_returns403() throws Exception {
        String token = tokenFor("analista.test@gmail.com");

        mockMvc.perform(post("/api/v1/auth/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAnalistaBody("otro.mas@gmail.com")))
                .andExpect(status().isForbidden());
    }

    @Test
    void createUser_asReferente_returns201() throws Exception {
        String token = tokenFor("referente.test@gmail.com");

        mockMvc.perform(post("/api/v1/auth/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAnalistaBody("nueva.alta@gmail.com")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("nueva.alta@gmail.com"))
                .andExpect(jsonPath("$.rol").value("ANALISTA_SINIESTROS"));
    }

    @Test
    void createUser_duplicateEmail_returns409() throws Exception {
        String token = tokenFor("referente.test@gmail.com");

        mockMvc.perform(post("/api/v1/auth/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAnalistaBody("analista.test@gmail.com")))
                .andExpect(status().isConflict());
    }

    @Test
    void createUser_roleAsegurado_returns400() throws Exception {
        String token = tokenFor("referente.test@gmail.com");

        mockMvc.perform(post("/api/v1/auth/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "asegurado.nuevo@gmail.com",
                                  "nombre": "Martina",
                                  "apellido": "Fernández",
                                  "rol": "ASEGURADO",
                                  "sector": "N/A"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listUsers_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listUsers_asAnalista_returns403() throws Exception {
        String token = tokenFor("analista.test@gmail.com");

        mockMvc.perform(get("/api/v1/auth/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void listUsers_asReferente_returns200WithAllSeededUsers() throws Exception {
        String token = tokenFor("referente.test@gmail.com");

        mockMvc.perform(get("/api/v1/auth/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].email").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "referente.test@gmail.com", "analista.test@gmail.com", "pendiente.test@gmail.com")));
    }

    @Test
    void listUsers_pendingUser_hasEstadoPending() throws Exception {
        String token = tokenFor("referente.test@gmail.com");

        mockMvc.perform(get("/api/v1/auth/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == 'pendiente.test@gmail.com')].estado").value("PENDING"))
                .andExpect(jsonPath("$[?(@.email == 'analista.test@gmail.com')].estado").value("ACTIVE"));
    }

    @Test
    void updateRole_withoutToken_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/auth/users/" + analistaId + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rol": "REFERENTE_ASEGURADORA"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateRole_asAnalista_returns403() throws Exception {
        String token = tokenFor("analista.test@gmail.com");

        mockMvc.perform(put("/api/v1/auth/users/" + referenteId + "/role")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rol": "ASEGURADO"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateRole_promoteAnalistaToReferente_returns200() throws Exception {
        String token = tokenFor("referente.test@gmail.com");

        mockMvc.perform(put("/api/v1/auth/users/" + analistaId + "/role")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rol": "REFERENTE_ASEGURADORA"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("REFERENTE_ASEGURADORA"));
    }

    @Test
    void updateRole_ownAccount_returns400() throws Exception {
        String token = tokenFor("referente.test@gmail.com");

        mockMvc.perform(put("/api/v1/auth/users/" + referenteId + "/role")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rol": "ANALISTA_SINIESTROS"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRole_unknownUser_returns404() throws Exception {
        String token = tokenFor("referente.test@gmail.com");

        mockMvc.perform(put("/api/v1/auth/users/999999/role")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rol": "ASEGURADO"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteUser_withoutToken_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/auth/users/" + analistaId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteUser_asAnalista_returns403() throws Exception {
        String token = tokenFor("analista.test@gmail.com");

        mockMvc.perform(delete("/api/v1/auth/users/" + referenteId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteUser_asReferente_returns204AndRemovesUser() throws Exception {
        String token = tokenFor("referente.test@gmail.com");

        mockMvc.perform(delete("/api/v1/auth/users/" + analistaId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(analistaId)).isEmpty();
    }

    @Test
    void deleteUser_ownAccount_returns400() throws Exception {
        String token = tokenFor("referente.test@gmail.com");

        mockMvc.perform(delete("/api/v1/auth/users/" + referenteId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteUser_unknownUser_returns404() throws Exception {
        String token = tokenFor("referente.test@gmail.com");

        mockMvc.perform(delete("/api/v1/auth/users/999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void resendInvite_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/users/" + pendienteId + "/resend-invite"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resendInvite_asAnalista_returns403() throws Exception {
        String token = tokenFor("analista.test@gmail.com");

        mockMvc.perform(post("/api/v1/auth/users/" + pendienteId + "/resend-invite")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void resendInvite_pendingUser_returns200WithNewToken() throws Exception {
        String token = tokenFor("referente.test@gmail.com");

        mockMvc.perform(post("/api/v1/auth/users/" + pendienteId + "/resend-invite")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PENDING"));

        User refreshed = userRepository.findById(pendienteId).orElseThrow();
        assertThat(refreshed.getInviteToken()).isNotEqualTo("tok-viejo");
        assertThat(refreshed.getInviteExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void resendInvite_activeUser_returns400() throws Exception {
        String token = tokenFor("referente.test@gmail.com");

        mockMvc.perform(post("/api/v1/auth/users/" + analistaId + "/resend-invite")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resendInvite_unknownUser_returns404() throws Exception {
        String token = tokenFor("referente.test@gmail.com");

        mockMvc.perform(post("/api/v1/auth/users/999999/resend-invite")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    private String newAnalistaBody(String email) {
        return """
                {
                  "email": "%s",
                  "nombre": "Nuevo",
                  "apellido": "Analista",
                  "rol": "ANALISTA_SINIESTROS",
                  "sector": "Siniestros Celulares",
                  "fechaIngreso": "2026-01-01"
                }
                """.formatted(email);
    }
}
