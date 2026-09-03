package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyCoverageRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cuál de las coberturas de la póliza responde por el hecho denunciado.
 *
 * <p>La pregunta existe porque una póliza tiene varias: la de celulares cubre robo Y hurto, cada
 * una con su suma asegurada, su franquicia y su carencia. Antes el expediente heredaba la única
 * cobertura de la póliza, así que un hurto se evaluaba contra la cobertura de robo.
 */
class PolicyCoverageResolverTest {

    private static final Long POLICY_ID = 7L;
    private static final Long ROBO_VIA_PUBLICA = 2L;
    private static final Long HURTO = 3L;

    private final PolicyCoverageRepository policyCoverageRepository = mock(PolicyCoverageRepository.class);
    private final RulesServiceClient rulesServiceClient = mock(RulesServiceClient.class);

    private final PolicyCoverageResolver resolver =
            new PolicyCoverageResolver(policyCoverageRepository, rulesServiceClient);

    @Test
    void picksTheCoverageThatCoversTheDenouncedCause() {
        givenContracted(coverage(1L, "Robo de celular"), coverage(2L, "Hurto"));
        // La cobertura de robo excluye el hurto; la de hurto, al revés.
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of(HURTO));
        when(rulesServiceClient.excludedClaimCauseIds(2L)).thenReturn(List.of(ROBO_VIA_PUBLICA));

        assertThat(resolver.resolveFor(POLICY_ID, HURTO).getCoverage().getName()).isEqualTo("Hurto");
        assertThat(resolver.resolveFor(POLICY_ID, ROBO_VIA_PUBLICA).getCoverage().getName())
                .isEqualTo("Robo de celular");
    }

    /**
     * Si el referente no cargó las exclusiones de ninguna cobertura, todas cubren todo y ninguna se
     * descarta: se toma la primera en el orden de la compañía. Es la misma cobertura que elegía el
     * código viejo, así que un tenant sin configurar no queda peor que antes — pero la respuesta es
     * tan precisa como las exclusiones cargadas.
     */
    @Test
    void withNoExclusionsConfigured_fallsBackToTheCompanysOrder() {
        givenContracted(coverage(1L, "Robo de celular"), coverage(2L, "Hurto"));
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of());
        when(rulesServiceClient.excludedClaimCauseIds(2L)).thenReturn(List.of());

        assertThat(resolver.resolveFor(POLICY_ID, HURTO).getDisplayOrder()).isEqualTo(1);
    }

    /** El precheck de elegibilidad corre antes de "¿qué te pasó?": todavía no hay hecho generador. */
    @Test
    void withNoClaimCause_takesTheFirstContractedCoverage() {
        givenContracted(coverage(1L, "Robo de celular"), coverage(2L, "Hurto"));

        assertThat(resolver.resolveFor(POLICY_ID, null).getCoverage().getName()).isEqualTo("Robo de celular");
    }

    /**
     * Ninguna cobertura cubre el hecho: no se corta acá. El expediente se crea y lo rechaza
     * PolicyEligibilityValidator, que le explica al asegurado que no está cubierto — un 422 sobre
     * coberturas no le diría nada.
     */
    @Test
    void whenNothingCoversTheCause_stillReturnsOneForTheEligibilityGateToReject() {
        givenContracted(coverage(1L, "Robo de celular"));
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of(HURTO));

        assertThat(resolver.resolveFor(POLICY_ID, HURTO)).isNotNull();
    }

    @Test
    void aPolicyWithNoCoverageOnFile_throws() {
        givenContracted();

        assertThatThrownBy(() -> resolver.resolveFor(POLICY_ID, HURTO))
                .isInstanceOf(UnresolvedCaseReferenceException.class);
    }

    /**
     * El bug más visible del modelo viejo: el wizard filtraba los hechos generadores por la ÚNICA
     * cobertura de la póliza, así que al titular de una póliza que cubre robo y hurto nunca se le
     * ofrecía hurto. Se ofrece si al menos una cobertura responde — la intersección de las listas
     * negras.
     */
    @Test
    void offersEveryCauseAtLeastOneCoverageAnswersFor() {
        givenContracted(coverage(1L, "Robo de celular"), coverage(2L, "Hurto"));
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of(HURTO));
        when(rulesServiceClient.excludedClaimCauseIds(2L)).thenReturn(List.of(ROBO_VIA_PUBLICA));

        // Ninguno de los dos queda excluido: cada uno lo cubre una de las coberturas.
        assertThat(resolver.excludedClaimCauseIds(POLICY_ID)).isEmpty();
    }

    /** Un hecho que TODAS las coberturas excluyen sí queda fuera del selector. */
    @Test
    void excludesOnlyWhatEveryCoverageExcludes() {
        givenContracted(coverage(1L, "Robo de celular"), coverage(2L, "Hurto"));
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of(HURTO, 99L));
        when(rulesServiceClient.excludedClaimCauseIds(2L)).thenReturn(List.of(ROBO_VIA_PUBLICA, 99L));

        assertThat(resolver.excludedClaimCauseIds(POLICY_ID)).containsExactly(99L);
    }

    private void givenContracted(PolicyCoverage... contracted) {
        when(policyCoverageRepository.findByPolicyIdOrderByDisplayOrderAsc(POLICY_ID))
                .thenReturn(List.of(contracted));
    }

    private PolicyCoverage coverage(Long coverageId, String name) {
        Coverage catalogued = new Coverage();
        catalogued.setId(coverageId);
        catalogued.setName(name);
        return PolicyCoverage.builder()
                .policyId(POLICY_ID)
                .coverage(catalogued)
                .displayOrder(coverageId.intValue())
                .sumInsured(new BigDecimal("500000"))
                .deductiblePct(new BigDecimal("10.00"))
                .build();
    }
}
