package ar.edu.utn.frba.arbiter.auth.controllers;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import ar.edu.utn.frba.arbiter.auth.models.entities.InsurerReferent;
import ar.edu.utn.frba.arbiter.common.models.entities.Role;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.common.models.entities.UserInsurer;
import ar.edu.utn.frba.arbiter.auth.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.InsurerReferentRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.RoleRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserInsurerRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.auth.services.Auth0UserProvisioner;
import ar.edu.utn.frba.arbiter.auth.services.JwtService;
import ar.edu.utn.frba.arbiter.auth.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H0002 - Alta de Usuarios. Tokens are issued straight from JwtService instead of hitting
 * /api/v1/auth/login: that endpoint validates against the real Auth0 now (there's no
 * DatabaseCredentialsAuthenticator to point at in a test), so login itself is covered by
 * Auth0AdapterTest with mocks — what's tested here is authorization and CRUD.
 */
// Dummy Auth0 domain: Auth0Config's AuthAPI bean needs a parseable URL to construct, even
// though nothing here ever calls the real Auth0 API — tokens are minted directly via JwtService.
@SpringBootTest(properties = "arbiter.auth.auth0.domain=example.auth0.com")
@AutoConfigureMockMvc
class UserControllerTest extends AbstractPersistenceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private InsurerRepository insurerRepository;

    @Autowired
    private UserInsurerRepository userInsurerRepository;

    @Autowired
    private InsurerReferentRepository insurerReferentRepository;

    @Autowired
    private ClaimsAnalystRepository claimsAnalystRepository;

    @Autowired
    private JwtService jwtService;

    /** Neutralizes real Auth0 Management API calls from deleteUser — login itself is
     * bypassed entirely here (tokens minted directly), Auth0Adapter never touches this. */
    @MockitoBean
    private Auth0UserProvisioner auth0UserProvisioner;

    private Long referenteId;
    private Long analistaId;
    private Long pendienteId;

    @BeforeEach
    void seedUsers() {
        userInsurerRepository.deleteAll();
        claimsAnalystRepository.deleteAll();
        insurerReferentRepository.deleteAll();
        userRepository.deleteAll();
        insurerRepository.deleteAll();
        roleRepository.deleteAll();

        Role referenteRole = roleRepository.save(Role.builder().code("REFERENTE_ASEGURADORA").name("Referente").build());
        Role analistaRole = roleRepository.save(Role.builder().code("ANALISTA_SINIESTROS").name("Analista").build());

        Insurer insurer = insurerRepository.save(Insurer.builder()
                .legalName("BBVA Seguros Argentina S.A.").name("BBVA Seguros")
                .taxId("30-50006423-0").active(true).schemaName("arbiter_bbva")
                .build());

        User referente = userRepository.save(User.builder()
                .email("referente.test@gmail.com").auth0Sub("auth0|referente-test")
                .roles(new HashSet<>(Set.of(referenteRole))).activated(true).build());
        referenteId = referente.getId();
        userInsurerRepository.save(UserInsurer.builder().user(referente).insurerId(insurer.getId()).build());
        insurerReferentRepository.save(InsurerReferent.builder().user(referente).name("Sofía").surname("Martínez").build());

        User analista = userRepository.save(User.builder()
                .email("analista.test@gmail.com").auth0Sub("auth0|analista-test")
                .roles(new HashSet<>(Set.of(analistaRole))).activated(true).build());
        analistaId = analista.getId();
        userInsurerRepository.save(UserInsurer.builder().user(analista).insurerId(insurer.getId()).build());
        claimsAnalystRepository.save(ClaimsAnalyst.builder()
                .user(analista).name("Lucas").surname("Gómez").email("analista.test@gmail.com").build());

        User pendiente = userRepository.save(User.builder()
                .email("pendiente.test@gmail.com").auth0Sub("pending:tok-viejo")
                .roles(new HashSet<>(Set.of(analistaRole)))
                .inviteToken("tok-viejo").inviteExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build());
        pendienteId = pendiente.getId();
        userInsurerRepository.save(UserInsurer.builder().user(pendiente).insurerId(insurer.getId()).build());
    }

    private String tokenFor(Long userId, UserRole rol) {
        User user = userRepository.findById(userId).orElseThrow();
        var issued = jwtService.issue(user, rol, "Test", "User", null, null, List.of(1L), "arbiter_bbva");
        return issued.token();
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
        String token = tokenFor(analistaId, UserRole.ANALISTA_SINIESTROS);

        mockMvc.perform(post("/api/v1/auth/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAnalistaBody("otro.mas@gmail.com")))
                .andExpect(status().isForbidden());
    }

    @Test
    void createUser_asReferente_returns201() throws Exception {
        String token = tokenFor(referenteId, UserRole.REFERENTE_ASEGURADORA);

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
        String token = tokenFor(referenteId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(post("/api/v1/auth/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAnalistaBody("analista.test@gmail.com")))
                .andExpect(status().isConflict());
    }

    @Test
    void createUser_roleAsegurado_returns400() throws Exception {
        String token = tokenFor(referenteId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(post("/api/v1/auth/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "asegurado.nuevo@gmail.com",
                                  "nombre": "Martina",
                                  "apellido": "Fernández",
                                  "rol": "ASEGURADO"
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
        String token = tokenFor(analistaId, UserRole.ANALISTA_SINIESTROS);

        mockMvc.perform(get("/api/v1/auth/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void listUsers_asReferente_returns200WithSameInsurerUsers() throws Exception {
        String token = tokenFor(referenteId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(get("/api/v1/auth/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].email").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "referente.test@gmail.com", "analista.test@gmail.com", "pendiente.test@gmail.com")));
    }

    @Test
    void listUsers_pendingUser_hasEstadoPending() throws Exception {
        String token = tokenFor(referenteId, UserRole.REFERENTE_ASEGURADORA);

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
        String token = tokenFor(analistaId, UserRole.ANALISTA_SINIESTROS);

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
        String token = tokenFor(referenteId, UserRole.REFERENTE_ASEGURADORA);

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
        String token = tokenFor(referenteId, UserRole.REFERENTE_ASEGURADORA);

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
        String token = tokenFor(referenteId, UserRole.REFERENTE_ASEGURADORA);

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
        String token = tokenFor(analistaId, UserRole.ANALISTA_SINIESTROS);

        mockMvc.perform(delete("/api/v1/auth/users/" + referenteId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteUser_asReferente_returns204AndRemovesUser() throws Exception {
        String token = tokenFor(referenteId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(delete("/api/v1/auth/users/" + analistaId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(analistaId)).isEmpty();
    }

    @Test
    void deleteUser_ownAccount_returns400() throws Exception {
        String token = tokenFor(referenteId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(delete("/api/v1/auth/users/" + referenteId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteUser_unknownUser_returns404() throws Exception {
        String token = tokenFor(referenteId, UserRole.REFERENTE_ASEGURADORA);

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
        String token = tokenFor(analistaId, UserRole.ANALISTA_SINIESTROS);

        mockMvc.perform(post("/api/v1/auth/users/" + pendienteId + "/resend-invite")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void resendInvite_pendingUser_returns200WithNewToken() throws Exception {
        String token = tokenFor(referenteId, UserRole.REFERENTE_ASEGURADORA);

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
        String token = tokenFor(referenteId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(post("/api/v1/auth/users/" + analistaId + "/resend-invite")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resendInvite_unknownUser_returns404() throws Exception {
        String token = tokenFor(referenteId, UserRole.REFERENTE_ASEGURADORA);

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
                  "rol": "ANALISTA_SINIESTROS"
                }
                """.formatted(email);
    }
}
