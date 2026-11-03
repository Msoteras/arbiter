package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import ar.edu.utn.frba.arbiter.rules.support.AbstractPersistenceIT;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AnalystRulesAccessTests extends AbstractPersistenceIT {

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

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/rules/branches",
            "/api/v1/rules/claim-causes?branchId=1",
            "/api/v1/rules/coverage-exclusions?coverageId=1",
            "/api/v1/rules/expert-derivation?branchId=1",
            "/api/v1/rules/repair-derivation?branchId=1",
            "/api/v1/rules/document-requirements?branchId=1&claimCauseId=1",
            "/api/v1/rules/fast-track?branchId=1&coverageId=1",
            "/api/v1/rules/fraud-record-rule",
            "/api/v1/rules/hard-rules?branchId=1&coverageId=1",
            "/api/v1/rules/insurer-hard-rules",
            "/api/v1/rules/resolution-target",
            "/api/v1/rules/history",
            "/api/v1/rules/history/rule-types",
            "/api/v1/rules/exclusions?branchId=1",
            "/api/v1/rules/business-rules?branchId=1",
            "/api/v1/rules/scoring"
    })
    void configurationRead_isOpenToTheAnalystButNotToTheInsured(String url) throws Exception {
        int asAnalyst = mockMvc.perform(get(url).header("Authorization", "Bearer " + tokenFor("ANALISTA_SINIESTROS")))
                .andReturn().getResponse().getStatus();
        assertThat(asAnalyst).isNotIn(401, 403);

        mockMvc.perform(get(url).header("Authorization", "Bearer " + tokenFor("ASEGURADO")))
                .andExpect(status().isForbidden());
    }

    @Test
    void onboardingStatus_staysReferenteOnly() throws Exception {
        mockMvc.perform(get("/api/v1/rules/onboarding")
                        .header("Authorization", "Bearer " + tokenFor("ANALISTA_SINIESTROS")))
                .andExpect(status().isForbidden());
    }

    static Stream<Arguments> configurationWrites() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/v1/rules/branches", "{\"name\":\"Hogar\"}"),
                Arguments.of(HttpMethod.PUT, "/api/v1/rules/branches/1", "{\"name\":\"Hogar\"}"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/rules/branches/1", null),
                Arguments.of(HttpMethod.PUT, "/api/v1/rules/coverage-exclusions?branchId=1&coverageId=1",
                        "{\"excludedClaimCauseIds\":[]}"),
                Arguments.of(HttpMethod.PUT, "/api/v1/rules/expert-derivation?branchId=1", "{\"enabled\":false}"),
                Arguments.of(HttpMethod.PUT, "/api/v1/rules/repair-derivation?branchId=1",
                        "{\"enabled\":false,\"claimCauseIds\":[]}"),
                Arguments.of(HttpMethod.PUT, "/api/v1/rules/document-requirements?branchId=1&claimCauseId=1", "[]"),
                Arguments.of(HttpMethod.PUT, "/api/v1/rules/fast-track?branchId=1&coverageId=1", "{}"),
                Arguments.of(HttpMethod.PUT, "/api/v1/rules/fraud-record-rule",
                        "{\"windowMonths\":36,\"blocksFastTrack\":false}"),
                Arguments.of(HttpMethod.PUT, "/api/v1/rules/hard-rules?branchId=1&coverageId=1", "[]"),
                Arguments.of(HttpMethod.PUT, "/api/v1/rules/insurer-hard-rules", "[]"),
                Arguments.of(HttpMethod.PUT, "/api/v1/rules/resolution-target", "{\"enabled\":false}"),
                Arguments.of(HttpMethod.PUT, "/api/v1/rules/exclusions?branchId=1", "[]"),
                Arguments.of(HttpMethod.PUT, "/api/v1/rules/business-rules?branchId=1", "[]"),
                Arguments.of(HttpMethod.PUT, "/api/v1/rules/scoring",
                        "{\"fullAnalysisOnFastTrack\":false,\"factors\":[],\"bands\":[]}"),
                Arguments.of(HttpMethod.PUT, "/api/v1/rules/onboarding/steps/COVERAGES", "{\"skipped\":true}"),
                Arguments.of(HttpMethod.POST, "/api/v1/rules/onboarding/resets", null));
    }

    @ParameterizedTest
    @MethodSource("configurationWrites")
    void configurationWrite_rejectsTheAnalyst(HttpMethod method, String url, String body) throws Exception {
        var call = request(method, url).header("Authorization", "Bearer " + tokenFor("ANALISTA_SINIESTROS"));
        if (body != null) {
            call.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        mockMvc.perform(call).andExpect(status().isForbidden());
    }
}
