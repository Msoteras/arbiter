package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.exceptions.RulesUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RulesServiceClientTest {

    private static final String BASE_URL = "http://rules-service:8081";
    private static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-for-hs256";

    private MockRestServiceServer server;
    private RulesServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RulesServiceClient(builder, BASE_URL, JWT_SECRET);
    }

    @Test
    void excludedClaimCauseIds_readsTheCoverageExclusions() {
        server.expect(requestTo(BASE_URL + "/api/v1/rules/internal/evaluable?coverageId=1"))
                .andRespond(withSuccess("""
                        {"rules": [
                          {"ruleType": "COVERAGE_EXCLUSION", "excludedClaimCauseIds": [3, 4]},
                          {"ruleType": "TEMPORAL", "excludedClaimCauseIds": null}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.excludedClaimCauseIds(1L)).containsExactly(3L, 4L);
    }

    @Test
    void excludedClaimCauseIds_rulesUnreachable_isReportedAsUnavailable() {
        server.expect(requestTo(BASE_URL + "/api/v1/rules/internal/evaluable?coverageId=1"))
                .andRespond(withException(new ConnectException("Connection refused")));

        assertThatThrownBy(() -> client.excludedClaimCauseIds(1L)).isInstanceOf(RulesUnavailableException.class);
    }

    @Test
    void excludedClaimCauseIds_rulesFailing_isReportedAsUnavailable() {
        server.expect(requestTo(BASE_URL + "/api/v1/rules/internal/evaluable?coverageId=1"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.excludedClaimCauseIds(1L)).isInstanceOf(RulesUnavailableException.class);
    }

    @Test
    void policyStandingRule_rulesUnreachable_isReportedAsUnavailable() {
        server.expect(requestTo(BASE_URL + "/api/v1/rules/internal/policy-standing"))
                .andRespond(withException(new ConnectException("Connection refused")));

        assertThatThrownBy(() -> client.policyStandingRule()).isInstanceOf(RulesUnavailableException.class);
    }
}
