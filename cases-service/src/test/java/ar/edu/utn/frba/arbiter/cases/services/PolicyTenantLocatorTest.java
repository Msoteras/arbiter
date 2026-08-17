package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * En qué esquema hay que dar de alta la denuncia. El {@code tenantSchema} del token se fija en el
 * login, antes de saber sobre qué póliza se va a denunciar; para alguien con pólizas en dos
 * aseguradoras eso alcanzaba sólo para una. Acá se prueba que el alta siga al dato (el número de
 * póliza) y no al sorteo del login — sin dejar de acotar la búsqueda a las aseguradoras firmadas.
 */
@ExtendWith(MockitoExtension.class)
class PolicyTenantLocatorTest {

    private static final String CALLER_TENANT = "arbiter_bbva";
    private static final String POLICY_NUMBER = "POL-CEL-2026-042";

    @Mock
    private InsurerRepository insurerRepository;

    @Mock
    private PolicyRepository policyRepository;

    @Mock
    private InsurerAdapter insurerAdapter;

    @InjectMocks
    private PolicyTenantLocator locator;

    @AfterEach
    void clearContext() {
        CallerContext.clear();
        TenantContext.clear();
    }

    private Insurer insurer(Long id, String schema, boolean active) {
        Insurer insurer = new Insurer();
        insurer.setId(id);
        insurer.setSchemaName(schema);
        insurer.setActive(active);
        return insurer;
    }

    private Policy policy() {
        return CaseFixtures.policy(POLICY_NUMBER, "Celular Protegido Premium");
    }

    /** El caso que motivó la clase: la póliza es de la segunda aseguradora, no la del login. */
    @Test
    void findsThePolicyInTheSecondInsurer() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                insurer(1L, "arbiter_bbva", true),
                insurer(2L, "arbiter_provincia", true)));
        // insurerAdapter no la tiene sincronizada / no responde: se cae al snapshot local.
        when(policyRepository.findByExternalPolicyNumber(POLICY_NUMBER))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(policy()));

        assertThat(locator.locate(POLICY_NUMBER)).isEqualTo("arbiter_provincia");
    }

    /**
     * Bug real del 16/8: un snapshot local viejo/duplicado en el esquema equivocado
     * (`arbiter_bbva.policy` con una fila de una póliza que en realidad es de Provincia) hacía
     * que el alta terminara en la aseguradora que no era, porque el código de entonces confiaba
     * en el primer esquema local que respondiera sin volver a preguntarle a la compañía. Ahora se
     * pregunta primero a la aseguradora (fuente de verdad) y esa respuesta gana aunque el snapshot
     * local tenga una fila — sin ese fallback ni siquiera hace falta.
     */
    @Test
    void trustsTheInsurerOverAStaleLocalSnapshotInTheWrongSchema() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("30.555.777", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                insurer(1L, "arbiter_bbva", true),
                insurer(2L, "arbiter_provincia", true)));
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.of(
                ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse.builder()
                        .policyNumber(POLICY_NUMBER)
                        .insurerId("2")
                        .build()));

        assertThat(locator.locate(POLICY_NUMBER)).isEqualTo("arbiter_provincia");
        verify(policyRepository, never()).findByExternalPolicyNumber(any());
    }

    @Test
    void unknownPolicyInEveryInsurer_fails422() {
        // Neither synced nor in the insurer DB: only then does the number name nothing.
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.empty());
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                insurer(1L, "arbiter_bbva", true),
                insurer(2L, "arbiter_provincia", true)));
        when(policyRepository.findByExternalPolicyNumber(POLICY_NUMBER))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> locator.locate(POLICY_NUMBER))
                .isInstanceOf(UnresolvedCaseReferenceException.class);
    }

    /**
     * Sondear esquemas no puede dejar el tenant movido: quien llama decide si lo cambia. Si esto
     * se rompe, el resto del request escribe en la aseguradora equivocada.
     */
    @Test
    void restoresCallerTenantAfterProbing() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                insurer(1L, "arbiter_bbva", true),
                insurer(2L, "arbiter_provincia", true)));
        when(policyRepository.findByExternalPolicyNumber(POLICY_NUMBER))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(policy()));

        locator.locate(POLICY_NUMBER);

        assertThat(TenantContext.get()).isEqualTo(CALLER_TENANT);
    }

    @Test
    void restoresCallerTenantWhenNothingResolves() {
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.empty());
        TenantContext.set(CALLER_TENANT);
        // Dos aseguradoras a propósito: con una sola no hay ambigüedad que sondear (ver
        // singleInsurer_skipsResolutionEntirely) y este test no ejercitaría el fallback al
        // snapshot local que quiere probar.
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                insurer(1L, "arbiter_bbva", true),
                insurer(2L, "arbiter_provincia", true)));
        when(policyRepository.findByExternalPolicyNumber(POLICY_NUMBER))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> locator.locate(POLICY_NUMBER))
                .isInstanceOf(UnresolvedCaseReferenceException.class);

        assertThat(TenantContext.get()).isEqualTo(CALLER_TENANT);
    }

    @Test
    void skipsInactiveInsurers() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                insurer(1L, "arbiter_bbva", false),
                insurer(2L, "arbiter_provincia", true)));

        // Una sola aseguradora activa queda tras filtrar: sin ambigüedad, se devuelve directo
        // sin consultar ni a la compañía ni al snapshot local.
        assertThat(locator.locate(POLICY_NUMBER)).isEqualTo("arbiter_provincia");
        verify(policyRepository, never()).findByExternalPolicyNumber(any());
    }

    @Test
    void singleInsurer_skipsResolutionEntirely() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(2L)))
                .thenReturn(List.of(insurer(2L, "arbiter_provincia", true)));

        assertThat(locator.locate(POLICY_NUMBER)).isEqualTo("arbiter_provincia");
        verify(insurerAdapter, never()).findPolicy(any());
        verify(policyRepository, never()).findByExternalPolicyNumber(any());
    }

    /**
     * Sin el claim (token viejo, o llamada sin usuario detrás) se opera contra el tenant ya
     * resuelto: el comportamiento de antes, no un error nuevo.
     */
    @Test
    void withoutInsurerIdsClaim_fallsBackToTheCurrentTenant() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(), CALLER_TENANT));

        assertThat(locator.locate(POLICY_NUMBER)).isEqualTo(CALLER_TENANT);
        verify(insurerRepository, never()).findAllById(any());
    }
}
