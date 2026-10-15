package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import ar.edu.utn.frba.arbiter.rules.support.AbstractPersistenceIT;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InternalEndpointSecurityTests extends AbstractPersistenceIT {

    private static final String SECRET = "test-secret-at-least-32-bytes-long-for-hs256";

    @Autowired
    private MockMvc mockMvc;

    private String tokenFor(String rol) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("test@arbiter.test")
                .claim("rol", rol)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(5))))
                .signWith(JwtSupport.key(SECRET))
                .compact();
    }

    private String serviceToken() {
        return JwtSupport.issueServiceToken(JwtSupport.key(SECRET), "classification-service");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/rules/internal/evaluable?coverageId=1",
            "/api/v1/rules/internal/coverage-limits?coverageId=1",
            "/api/v1/rules/internal/scoring",
            "/api/v1/rules/internal/expert-derivation?branchId=1",
            "/api/v1/rules/internal/repair-derivation?branchId=1",
            "/api/v1/rules/internal/fast-track?coverageId=1",
            "/api/v1/rules/internal/fraud-record-rule",
            "/api/v1/rules/internal/policy-standing",
            "/api/v1/rules/internal/resolution-target",
            "/api/v1/rules/internal/rule-texts?coverageId=1",
            "/api/v1/rules/document-requirements/internal?coverageId=1&claimCause=Hurto"
    })
    void internalEndpoint_rejectsUsersAndAcceptsServiceTokens(String url) throws Exception {
        for (String rol : new String[] {"ASEGURADO", "ANALISTA_SINIESTROS", "REFERENTE_ASEGURADORA"}) {
            mockMvc.perform(get(url).header("Authorization", "Bearer " + tokenFor(rol)))
                    .andExpect(status().isForbidden());
        }

        int withServiceToken = mockMvc.perform(get(url).header("Authorization", "Bearer " + serviceToken()))
                .andReturn().getResponse().getStatus();
        assertThat(withServiceToken).isNotIn(401, 403);
    }

    @Test
    void documentRequirementsForBranch_staysOpenToTheInsured() throws Exception {
        int status = mockMvc.perform(get("/api/v1/rules/document-requirements/for-branch")
                        .param("branch", "Celulares").param("claimCause", "Hurto")
                        .header("Authorization", "Bearer " + tokenFor("ASEGURADO")))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);
    }
}
