package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import io.jsonwebtoken.Jwts;
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
class AnalystConfigurationAccessTests extends AbstractPersistenceIT {

    private static final String SECRET = "test-secret-at-least-32-bytes-long-for-hs256";
    private static final String COVERAGE =
            "{\"name\":\"Robo\",\"deductPendingInstallments\":false,\"deductOverdueBalance\":false}";
    private static final String PROVIDER =
            "{\"name\":\"Estudio Pérez\",\"email\":\"estudio@arbiter.test\",\"branchIds\":[1],\"active\":true}";

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
            "/api/v1/coverages?branchId=1",
            "/api/v1/coverages/insurer-names?branchId=1",
            "/api/v1/coverages/detailed?branchId=1",
            "/api/v1/coverages/summary",
            "/api/v1/expert-firms",
            "/api/v1/settlement-authorities"
    })
    void configurationRead_isOpenToTheAnalystButNotToTheInsured(String url) throws Exception {
        int asAnalyst = mockMvc.perform(get(url).header("Authorization", "Bearer " + tokenFor("ANALISTA_SINIESTROS")))
                .andReturn().getResponse().getStatus();
        assertThat(asAnalyst).isNotIn(401, 403);

        mockMvc.perform(get(url).header("Authorization", "Bearer " + tokenFor("ASEGURADO")))
                .andExpect(status().isForbidden());
    }

    static Stream<Arguments> configurationWrites() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/v1/coverages?branchId=1", COVERAGE),
                Arguments.of(HttpMethod.PUT, "/api/v1/coverages/1", COVERAGE),
                Arguments.of(HttpMethod.DELETE, "/api/v1/coverages/1", null),
                Arguments.of(HttpMethod.POST, "/api/v1/expert-firms", PROVIDER),
                Arguments.of(HttpMethod.PUT, "/api/v1/expert-firms/1", PROVIDER),
                Arguments.of(HttpMethod.DELETE, "/api/v1/expert-firms/1", null),
                Arguments.of(HttpMethod.PUT, "/api/v1/settlement-authorities/1", "{\"maxAmount\":1000}"));
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
