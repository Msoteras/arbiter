package ar.edu.utn.frba.arbiter.auth.controllers;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.InsurerReferent;
import ar.edu.utn.frba.arbiter.common.models.entities.Role;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.common.models.entities.UserInsurer;
import ar.edu.utn.frba.arbiter.auth.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.InsuredRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tokens are minted straight from JwtService because login validates against the real Auth0;
 * login is covered by Auth0AdapterTest. The dummy Auth0 domain only makes the AuthAPI bean buildable.
 */
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
    private InsuredRepository insuredRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Auth0UserProvisioner auth0UserProvisioner;

    private Long referentId;
    private Long analystId;
    private Long pendingId;
    private Long otherInsurerAnalystId;
    private Long sharedInsuredId;
    private Long provinciaId;

    @BeforeEach
    void seedUsers() {
        // Stands in for any table that records activity (case_message, case_status_history...).
        jdbcTemplate.execute("DROP TABLE IF EXISTS arbiter_common.activity_probe");
        userInsurerRepository.deleteAll();
        insuredRepository.deleteAll();
        claimsAnalystRepository.deleteAll();
        insurerReferentRepository.deleteAll();
        userRepository.deleteAll();
        insurerRepository.deleteAll();
        roleRepository.deleteAll();

        Role referentRole = roleRepository.save(Role.builder().code("REFERENTE_ASEGURADORA").name("Referente").build());
        Role analystRole = roleRepository.save(Role.builder().code("ANALISTA_SINIESTROS").name("Analista").build());
        Role insuredRole = roleRepository.save(Role.builder().code("ASEGURADO").name("Asegurado").build());

        Insurer insurer = insurerRepository.save(Insurer.builder()
                .legalName("BBVA Seguros Argentina S.A.").name("BBVA Seguros")
                .taxId("30-50006423-0").active(true).schemaName("arbiter_bbva")
                .build());

        User referent = userRepository.save(User.builder()
                .email("referente.test@gmail.com").auth0Sub("auth0|referente-test")
                .roles(new HashSet<>(Set.of(referentRole))).activated(true).build());
        referentId = referent.getId();
        userInsurerRepository.save(UserInsurer.builder().user(referent).insurerId(insurer.getId()).build());
        insurerReferentRepository.save(InsurerReferent.builder().user(referent).name("Sofía").surname("Martínez").build());

        User analyst = userRepository.save(User.builder()
                .email("analista.test@gmail.com").auth0Sub("auth0|analista-test")
                .roles(new HashSet<>(Set.of(analystRole))).activated(true).build());
        analystId = analyst.getId();
        userInsurerRepository.save(UserInsurer.builder().user(analyst).insurerId(insurer.getId()).build());
        claimsAnalystRepository.save(ClaimsAnalyst.builder()
                .user(analyst).name("Lucas").surname("Gómez").email("analista.test@gmail.com").build());

        User pending = userRepository.save(User.builder()
                .email("pendiente.test@gmail.com").auth0Sub("pending:tok-viejo")
                .roles(new HashSet<>(Set.of(analystRole)))
                .inviteToken("tok-viejo").inviteExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build());
        pendingId = pending.getId();
        userInsurerRepository.save(UserInsurer.builder().user(pending).insurerId(insurer.getId()).build());

        Insurer provincia = insurerRepository.save(Insurer.builder()
                .legalName("Provincia Seguros S.A.").name("Provincia Seguros")
                .taxId("30-52750816-5").active(true).schemaName("arbiter_provincia")
                .build());
        provinciaId = provincia.getId();

        User otherAnalyst = userRepository.save(User.builder()
                .email("analista.provincia@gmail.com").auth0Sub("auth0|analista-provincia")
                .roles(new HashSet<>(Set.of(analystRole))).build());
        otherInsurerAnalystId = otherAnalyst.getId();
        userInsurerRepository.save(UserInsurer.builder().user(otherAnalyst).insurerId(provincia.getId()).build());

        User shared = userRepository.save(User.builder()
                .email("martina.test@gmail.com").auth0Sub("auth0|martina-test")
                .roles(new HashSet<>(Set.of(insuredRole))).activated(true).build());
        sharedInsuredId = shared.getId();
        userInsurerRepository.save(UserInsurer.builder().user(shared).insurerId(insurer.getId()).build());
        userInsurerRepository.save(UserInsurer.builder().user(shared).insurerId(provincia.getId()).build());
        insuredRepository.save(Insured.builder()
                .user(shared).name("Martina").surname("López").dni("30111222").email("martina.test@gmail.com")
                .build());
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
                        .content(newAnalystBody("primera.vez@gmail.com")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createUser_asAnalyst_returns403() throws Exception {
        String token = tokenFor(analystId, UserRole.ANALISTA_SINIESTROS);

        mockMvc.perform(post("/api/v1/auth/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAnalystBody("otro.mas@gmail.com")))
                .andExpect(status().isForbidden());
    }

    @Test
    void createUser_asReferent_returns201() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(post("/api/v1/auth/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAnalystBody("nueva.alta@gmail.com")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("nueva.alta@gmail.com"))
                .andExpect(jsonPath("$.rol").value("ANALISTA_SINIESTROS"));
    }

    @Test
    void createUser_duplicateEmail_returns409() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(post("/api/v1/auth/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newAnalystBody("analista.test@gmail.com")))
                .andExpect(status().isConflict());
    }

    @Test
    void createUser_roleInsured_returns400() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

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
    void listUsers_asAnalyst_returns403() throws Exception {
        String token = tokenFor(analystId, UserRole.ANALISTA_SINIESTROS);

        mockMvc.perform(get("/api/v1/auth/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void listUsers_asReferent_returns200WithSameInsurerUsers() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(get("/api/v1/auth/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].email").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "referente.test@gmail.com", "analista.test@gmail.com", "pendiente.test@gmail.com",
                        "martina.test@gmail.com")));
    }

    @Test
    void listUsers_pendingUser_hasStatusPending() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(get("/api/v1/auth/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == 'pendiente.test@gmail.com')].estado").value("PENDING"))
                .andExpect(jsonPath("$[?(@.email == 'analista.test@gmail.com')].estado").value("ACTIVE"));
    }

    @Test
    void updateRole_withoutToken_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/auth/users/" + analystId + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rol": "REFERENTE_ASEGURADORA"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateRole_asAnalyst_returns403() throws Exception {
        String token = tokenFor(analystId, UserRole.ANALISTA_SINIESTROS);

        mockMvc.perform(put("/api/v1/auth/users/" + referentId + "/role")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rol": "ASEGURADO"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateRole_promoteAnalystToReferent_returns200AndCreatesTheReferentProfile() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(put("/api/v1/auth/users/" + analystId + "/role")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rol": "REFERENTE_ASEGURADORA"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("REFERENTE_ASEGURADORA"))
                .andExpect(jsonPath("$.nombre").value("Lucas"));

        assertThat(insurerReferentRepository.findByUserId(analystId)).isPresent();
    }

    @Test
    void updateRole_insuredTarget_returns400AndKeepsTheRole() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(put("/api/v1/auth/users/" + sharedInsuredId + "/role")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rol": "ANALISTA_SINIESTROS"}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.findById(sharedInsuredId).orElseThrow().getRoles())
                .extracting(Role::getCode).containsExactly("ASEGURADO");
        assertThat(claimsAnalystRepository.findByUserId(sharedInsuredId)).isEmpty();
    }

    @Test
    void updateRole_analystToInsured_returns400() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(put("/api/v1/auth/users/" + analystId + "/role")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rol": "ASEGURADO"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRole_userOfAnotherInsurer_returns404AndKeepsTheRole() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(put("/api/v1/auth/users/" + otherInsurerAnalystId + "/role")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rol": "REFERENTE_ASEGURADORA"}
                                """))
                .andExpect(status().isNotFound());

        assertThat(userRepository.findById(otherInsurerAnalystId).orElseThrow().getRoles())
                .extracting(Role::getCode).containsExactly("ANALISTA_SINIESTROS");
    }

    @Test
    void updateRole_ownAccount_returns400() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(put("/api/v1/auth/users/" + referentId + "/role")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rol": "ANALISTA_SINIESTROS"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRole_unknownUser_returns404() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

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
        mockMvc.perform(delete("/api/v1/auth/users/" + analystId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteUser_asAnalyst_returns403() throws Exception {
        String token = tokenFor(analystId, UserRole.ANALISTA_SINIESTROS);

        mockMvc.perform(delete("/api/v1/auth/users/" + referentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteUser_asReferent_returns204AndRemovesUser() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(delete("/api/v1/auth/users/" + analystId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(analystId)).isEmpty();
        assertThat(claimsAnalystRepository.findByUserId(analystId)).isEmpty();
        verify(auth0UserProvisioner).deleteUser("analista.test@gmail.com");
    }

    @Test
    void deleteUser_userOfAnotherInsurer_returns404AndKeepsTheAccount() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(delete("/api/v1/auth/users/" + otherInsurerAnalystId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        assertThat(userRepository.findById(otherInsurerAnalystId)).isPresent();
        assertThat(userInsurerRepository.findByUserId(otherInsurerAnalystId)).hasSize(1);
        verify(auth0UserProvisioner, never()).deleteUser(any());
    }

    @Test
    void deleteUser_withRecordedActivity_returns409AndChangesNothing() throws Exception {
        jdbcTemplate.execute("""
                CREATE TABLE arbiter_common.activity_probe (
                    user_id BIGINT NOT NULL REFERENCES arbiter_common.users(id))
                """);
        jdbcTemplate.update("INSERT INTO arbiter_common.activity_probe (user_id) VALUES (?)", analystId);
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(delete("/api/v1/auth/users/" + analystId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("El usuario tiene actividad registrada y no se puede eliminar"));

        assertThat(userRepository.findById(analystId)).isPresent();
        assertThat(userInsurerRepository.findByUserId(analystId)).hasSize(1);
        assertThat(claimsAnalystRepository.findByUserId(analystId)).isPresent();
        verify(auth0UserProvisioner, never()).deleteUser(any());
    }

    @Test
    void deleteUser_insuredInTwoInsurers_onlyUnlinksTheCallersInsurer() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(delete("/api/v1/auth/users/" + sharedInsuredId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(sharedInsuredId)).isPresent();
        assertThat(userInsurerRepository.findByUserId(sharedInsuredId))
                .extracting(UserInsurer::getInsurerId).containsExactly(provinciaId);
        verify(auth0UserProvisioner, never()).deleteUser(any());
    }

    @Test
    void deleteUser_ownAccount_returns400() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(delete("/api/v1/auth/users/" + referentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteUser_unknownUser_returns404() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(delete("/api/v1/auth/users/999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void resendInvite_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/users/" + pendingId + "/resend-invite"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resendInvite_asAnalyst_returns403() throws Exception {
        String token = tokenFor(analystId, UserRole.ANALISTA_SINIESTROS);

        mockMvc.perform(post("/api/v1/auth/users/" + pendingId + "/resend-invite")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void resendInvite_pendingUser_returns200WithNewToken() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(post("/api/v1/auth/users/" + pendingId + "/resend-invite")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PENDING"));

        User refreshed = userRepository.findById(pendingId).orElseThrow();
        assertThat(refreshed.getInviteToken()).isNotEqualTo("tok-viejo");
        assertThat(refreshed.getInviteExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void resendInvite_activeUser_returns400() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(post("/api/v1/auth/users/" + analystId + "/resend-invite")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resendInvite_userOfAnotherInsurer_returns404() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(post("/api/v1/auth/users/" + otherInsurerAnalystId + "/resend-invite")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void resendInvite_unknownUser_returns404() throws Exception {
        String token = tokenFor(referentId, UserRole.REFERENTE_ASEGURADORA);

        mockMvc.perform(post("/api/v1/auth/users/999999/resend-invite")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    private String newAnalystBody(String email) {
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
