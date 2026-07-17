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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @BeforeEach
    void seedUsers() {
        userRepository.deleteAll();

        userRepository.save(User.builder()
                .email("referente.test@arbiter.test")
                .passwordHash(passwordEncoder.encode("changeme123"))
                .nombre("Sofía")
                .apellido("Martínez")
                .rol(UserRole.REFERENTE_ASEGURADORA)
                .build());

        userRepository.save(User.builder()
                .email("analista.test@arbiter.test")
                .passwordHash(passwordEncoder.encode("changeme123"))
                .nombre("Lucas")
                .apellido("Gómez")
                .rol(UserRole.ANALISTA_SINIESTROS)
                .build());
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
                        .content(newAnalistaBody("primera.vez@arbiter.test")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createUser_asAnalista_returns403() throws Exception {
        String token = tokenFor("analista.test@arbiter.test");

        mockMvc.perform(post("/api/v1/auth/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAnalistaBody("otro.mas@arbiter.test")))
                .andExpect(status().isForbidden());
    }

    @Test
    void createUser_asReferente_returns201() throws Exception {
        String token = tokenFor("referente.test@arbiter.test");

        mockMvc.perform(post("/api/v1/auth/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAnalistaBody("nueva.alta@arbiter.test")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("nueva.alta@arbiter.test"))
                .andExpect(jsonPath("$.rol").value("ANALISTA_SINIESTROS"));
    }

    @Test
    void createUser_duplicateEmail_returns409() throws Exception {
        String token = tokenFor("referente.test@arbiter.test");

        mockMvc.perform(post("/api/v1/auth/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAnalistaBody("analista.test@arbiter.test")))
                .andExpect(status().isConflict());
    }

    @Test
    void createUser_roleAsegurado_returns400() throws Exception {
        String token = tokenFor("referente.test@arbiter.test");

        mockMvc.perform(post("/api/v1/auth/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "asegurado.nuevo@arbiter.test",
                                  "nombre": "Martina",
                                  "apellido": "Fernández",
                                  "password": "changeme123",
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
        String token = tokenFor("analista.test@arbiter.test");

        mockMvc.perform(get("/api/v1/auth/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void listUsers_asReferente_returns200WithBothSeededUsers() throws Exception {
        String token = tokenFor("referente.test@arbiter.test");

        mockMvc.perform(get("/api/v1/auth/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].email").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "referente.test@arbiter.test", "analista.test@arbiter.test")));
    }

    private String newAnalistaBody(String email) {
        return """
                {
                  "email": "%s",
                  "nombre": "Nuevo",
                  "apellido": "Analista",
                  "password": "changeme123",
                  "rol": "ANALISTA_SINIESTROS",
                  "sector": "Siniestros Celulares",
                  "fechaIngreso": "2026-01-01"
                }
                """.formatted(email);
    }
}
