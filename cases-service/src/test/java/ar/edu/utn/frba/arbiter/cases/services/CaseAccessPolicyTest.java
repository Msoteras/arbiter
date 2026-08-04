package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
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
 * Quién puede leer un expediente ajeno. El modo de falla acá es invisible — alguien viendo un
 * expediente que no le corresponde se ve igual que viendo el propio — así que lo que se prueba no
 * es que "funcione" sino que <b>niegue</b>, y que niegue de la forma correcta (404, no 403).
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
        // 404 y no 403 a propósito: un 403 confirma que el expediente existe, y como los ids son
        // secuenciales alcanzaría para mapear la tabla probando de a uno.
        assertThatThrownBy(() -> policy.assertCanRead(someoneElses))
                .isInstanceOf(CaseNotFoundException.class);
    }

    @Test
    void analystReadingAnyCaseInTheirTenant_isAllowed() {
        authenticateAs("ROLE_ANALISTA_SINIESTROS");
        CallerContext.set(new CallerContext.Caller(null, List.of(1L), "arbiter_bbva"));

        // Revisar siniestros ajenos es literalmente el trabajo del analista; el esquema ya lo
        // acota a una sola aseguradora.
        assertThat(policy.canRead(caseOwnedBy("11.222.333"))).isTrue();
    }

    @Test
    void referentReadingAnyCaseInTheirTenant_isAllowed() {
        authenticateAs("ROLE_REFERENTE_ASEGURADORA");
        CallerContext.set(new CallerContext.Caller(null, List.of(1L), "arbiter_bbva"));

        assertThat(policy.canRead(caseOwnedBy("11.222.333"))).isTrue();
    }

    /**
     * Un token de asegurado sin DNI no puede terminar viendo todo: sin ese dato no hay con qué
     * comparar, y el default seguro es negar.
     */
    @Test
    void insuredWithoutDniClaim_isDenied() {
        authenticateAs("ROLE_ASEGURADO");
        CallerContext.set(new CallerContext.Caller(null, List.of(1L), "arbiter_bbva"));

        assertThat(policy.canRead(caseOwnedBy("42.987.654"))).isFalse();
    }

    @Test
    void unauthenticatedRequest_isNotTreatedAsInsured() {
        // Sin Authentication no hay rol ASEGURADO que restringir. No es un agujero: llegar acá ya
        // exige haber pasado el filtro de seguridad, que rechaza el anónimo antes.
        assertThat(policy.currentUserIsInsured()).isFalse();
    }
}
