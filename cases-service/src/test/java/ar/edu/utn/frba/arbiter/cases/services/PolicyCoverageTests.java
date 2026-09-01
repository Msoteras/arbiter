package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CoverageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyCoverageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.cases.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * El smoke test del bug de coberturas, contra Postgres real: una póliza que cubre <b>robo y
 * hurto</b> se importa entera, y un hurto denunciado sobre ella se resuelve contra la cobertura de
 * hurto — con la suma asegurada de hurto, no la de robo.
 *
 * <p>Existe porque ningún test automatizado recorría ese camino. Los unitarios de
 * {@link PolicyCoverageResolver} tienen el repositorio mockeado, y {@code CaseServiceImplTest}
 * mockea el resolver entero: entre los dos no queda nadie ejercitando el mapeo JPA de
 * {@code policy_coverage} ni la query que lo lee. Es la clase de agujero por el que pasó el
 * {@code @EntityGraph} colgado de {@code Policy.coverage} — invisible con el repositorio mockeado,
 * y una excepción en cada alta de denuncia contra Hibernate real.
 *
 * <p>Lo que NO cubre: el selector del wizard (es una pantalla) y el salto REST a
 * classification-service. Eso sigue siendo verificación manual.
 */
@SpringBootTest
@Transactional
class PolicyCoverageTests extends AbstractPersistenceIT {

    private static final String POLICY_NUMBER = "POL-CEL-2026-042";

    /** Ids de {@code claim_cause} sembrados por {@link #seedCatalog()}. */
    private Long roboViaPublica;
    private Long hurto;

    @MockitoBean private InsurerAdapter insurerAdapter;
    /** Cliente REST a rules-service: acá no hay rules-service levantado, así que se mockea. */
    @MockitoBean private RulesServiceClient rulesServiceClient;

    @Autowired private PolicySynchronizer synchronizer;
    @Autowired private PolicyCoverageResolver resolver;
    @Autowired private PolicyRepository policyRepository;
    @Autowired private PolicyCoverageRepository policyCoverageRepository;
    @Autowired private CoverageRepository coverageRepository;

    private Coverage robo;
    private Coverage hurtoCoverage;

    @BeforeEach
    void seedCatalog() {
        robo = coverage("Robo de celular");
        hurtoCoverage = coverage("Hurto");
        // Los ids de hecho generador del ramo Celulares en el seed real: 2 = Robo en vía pública,
        // 3 = Hurto. No hace falta la fila de claim_cause: lo único que se cruza contra ellos son
        // las listas de exclusión, que llegan por REST.
        roboViaPublica = 2L;
        hurto = 3L;

        // Cada cobertura cubre un solo hecho generador de su ramo, que es como quedó el seed tras
        // arreglar las filas COVERAGE_EXCLUSION.
        when(rulesServiceClient.excludedClaimCauseIds(robo.getId())).thenReturn(List.of(hurto));
        when(rulesServiceClient.excludedClaimCauseIds(hurtoCoverage.getId()))
                .thenReturn(List.of(roboViaPublica));
    }

    /**
     * El corazón del bug: la compañía devuelve dos coberturas y antes se guardaba
     * {@code coverages.get(0)}. Acá tienen que quedar las dos, cada una con SU suma asegurada.
     */
    @Test
    void importsEveryCoverageOfThePolicy() {
        givenTheCompanyHasThePolicy();

        Policy imported = synchronizer.importFromInsurer(POLICY_NUMBER, 1L);

        List<PolicyCoverage> contracted =
                policyCoverageRepository.findByPolicyIdOrderByDisplayOrderAsc(imported.getId());
        assertThat(contracted).extracting(pc -> pc.getCoverage().getName())
                .containsExactly("Robo de celular", "Hurto");
        assertThat(contracted).extracting(PolicyCoverage::getSumInsured)
                .containsExactly(new BigDecimal("1300000.00"), new BigDecimal("650000.00"));
    }

    /**
     * El síntoma que veía el analista: los expedientes de hurto colgados de Robo de celular, con la
     * suma asegurada de robo congelada encima.
     */
    @Test
    void aHurtoResolvesAgainstTheHurtoCoverage_withItsOwnSumInsured() {
        givenTheCompanyHasThePolicy();
        Policy imported = synchronizer.importFromInsurer(POLICY_NUMBER, 1L);

        PolicyCoverage answering = resolver.resolveFor(imported.getId(), hurto);

        assertThat(answering.getCoverage().getName()).isEqualTo("Hurto");
        // El número que se congela en el expediente: el de hurto, la mitad que el de robo.
        assertThat(answering.getSumInsured()).isEqualByComparingTo("650000");
    }

    /** La contracara, para que el test no pase por elegir siempre la segunda. */
    @Test
    void aRoboResolvesAgainstTheRoboCoverage() {
        givenTheCompanyHasThePolicy();
        Policy imported = synchronizer.importFromInsurer(POLICY_NUMBER, 1L);

        PolicyCoverage answering = resolver.resolveFor(imported.getId(), roboViaPublica);

        assertThat(answering.getCoverage().getName()).isEqualTo("Robo de celular");
        assertThat(answering.getSumInsured()).isEqualByComparingTo("1300000");
    }

    /**
     * Lo que el asegurado veía en el wizard: con una sola cobertura guardada, Hurto no aparecía
     * entre los hechos que podía denunciar aunque su póliza lo cubriera. Se ofrece si al menos una
     * cobertura responde.
     */
    @Test
    void theWizardOffersBothCauses_becauseOneCoverageAnswersForEach() {
        givenTheCompanyHasThePolicy();
        Policy imported = synchronizer.importFromInsurer(POLICY_NUMBER, 1L);

        assertThat(resolver.excludedClaimCauseIds(imported.getId())).isEmpty();
    }

    /**
     * La póliza ya no lleva cobertura: la lectura por número tiene que seguir funcionando contra
     * Hibernate real. Es exactamente lo que rompía el {@code @EntityGraph} que quedó apuntando a
     * un atributo borrado, y ningún unitario lo veía.
     */
    @Test
    void findingThePolicyByNumberStillWorks() {
        givenTheCompanyHasThePolicy();
        synchronizer.importFromInsurer(POLICY_NUMBER, 1L);

        assertThat(policyRepository.findByExternalPolicyNumber(POLICY_NUMBER)).isPresent();
    }

    private void givenTheCompanyHasThePolicy() {
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.of(PolicyResponse.builder()
                .policyNumber(POLICY_NUMBER)
                .product("Celular Protegido Premium")
                .effectiveFrom(LocalDateTime.now().minusMonths(6))
                .effectiveTo(LocalDateTime.now().plusMonths(6))
                .coverages(List.of(
                        remoteCoverage("Robo de celular", "1300000.00"),
                        remoteCoverage("Hurto", "650000.00")))
                .build()));
    }

    private PolicyResponse.Coverage remoteCoverage(String name, String sumInsured) {
        return PolicyResponse.Coverage.builder()
                .code("COB-" + name)
                .description(name)
                .insuredAmount(new BigDecimal(sumInsured))
                .deductiblePct(new BigDecimal("10.00"))
                .build();
    }

    private Coverage coverage(String name) {
        return coverageRepository.findByName(name).orElseGet(() -> coverageRepository.save(
                Coverage.builder().name(name).branchId(1L).build()));
    }
}
