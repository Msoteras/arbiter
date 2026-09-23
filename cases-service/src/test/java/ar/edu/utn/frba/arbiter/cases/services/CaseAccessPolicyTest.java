package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who may read someone else's case. The failure mode is invisible (a foreign case looks just like
 * one's own), so what's tested is that it <b>denies</b>, and denies correctly (404, not 403).
 */
class CaseAccessPolicyTest {

    private final CaseAccessPolicy policy = new CaseAccessPolicy();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        CallerContext.clear();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone@arbiter.test", "n/a",
                        List.of(new SimpleGrantedAuthority(role))));
    }

    private Case caseOwnedBy(String dni) {
        Case caseRecord = new Case();
        caseRecord.setId(42L);
        caseRecord.setInsured(CaseFixtures.insured(dni, "Martina", "Soteras"));
        return caseRecord;
    }

    @Test
    void insuredReadingOwnCase_isAllowed() {
        authenticateAs("ROLE_ASEGURADO");
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L), "arbiter_bbva"));

        assertThat(policy.canRead(caseOwnedBy("42.987.654"))).isTrue();
    }

    @Test
    void insuredReadingSomeoneElsesCase_isDeniedAs404() {
        authenticateAs("ROLE_ASEGURADO");
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L), "arbiter_bbva"));
        Case someoneElses = caseOwnedBy("11.222.333");

        assertThat(policy.canRead(someoneElses)).isFalse();
        // 404 rather than 403 on purpose: a 403 confirms the case exists, and with sequential ids
        // that's enough to map the whole table.
        assertThatThrownBy(() -> policy.assertCanRead(someoneElses))
                .isInstanceOf(CaseNotFoundException.class);
    }

    @Test
    void analystReadingAnyCaseInTheirTenant_isAllowed() {
        authenticateAs("ROLE_ANALISTA_SINIESTROS");
        CallerContext.set(new CallerContext.Caller(null, List.of(1L), "arbiter_bbva"));

        // Reviewing others' claims is the analyst's job; the schema already limits it to one
        // insurer.
        assertThat(policy.canRead(caseOwnedBy("11.222.333"))).isTrue();
    }

    @Test
    void referentReadingAnyCaseInTheirTenant_isAllowed() {
        authenticateAs("ROLE_REFERENTE_ASEGURADORA");
        CallerContext.set(new CallerContext.Caller(null, List.of(1L), "arbiter_bbva"));

        assertThat(policy.canRead(caseOwnedBy("11.222.333"))).isTrue();
    }

    /** Without a DNI there's nothing to compare against, so the safe default is to deny. */
    @Test
    void insuredWithoutDniClaim_isDenied() {
        authenticateAs("ROLE_ASEGURADO");
        CallerContext.set(new CallerContext.Caller(null, List.of(1L), "arbiter_bbva"));

        assertThat(policy.canRead(caseOwnedBy("42.987.654"))).isFalse();
    }

    @Test
    void unauthenticatedRequest_isNotTreatedAsInsured() {
        // Not a hole: reaching this point requires the security filter, which rejects anonymous
        // requests first.
        assertThat(policy.currentUserIsInsured()).isFalse();
    }

    @Test
    void currentAssignmentActor_forAnalyst_isAnalyst() {
        authenticateAs("ROLE_ANALISTA_SINIESTROS");

        assertThat(policy.currentAssignmentActor()).isEqualTo(StatusChangeActor.ANALYST);
    }

    @Test
    void currentAssignmentActor_forReferent_isReferent() {
        // The assignment endpoint is shared by both roles; the history must tell them apart instead
        // of attributing everything to the analyst.
        authenticateAs("ROLE_REFERENTE_ASEGURADORA");

        assertThat(policy.currentAssignmentActor()).isEqualTo(StatusChangeActor.REFERENT);
    }
}
