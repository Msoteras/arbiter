package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.cases.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CoverageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyCoverageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * On-demand sync of a policy's local snapshot (decision #10). What's being tested is that a
 * policy the company has and Arbiter doesn't gets copied instead of rejected — and that what does
 * fail is the coverage, which is referente configuration and can't be invented.
 */
class PolicySynchronizerTest {

    private static final String POLICY_NUMBER = "POL-TEC-2026-050";

    private final InsurerAdapter insurerAdapter = mock(InsurerAdapter.class);
    private final PolicyRepository policyRepository = mock(PolicyRepository.class);
    private final CoverageRepository coverageRepository = mock(CoverageRepository.class);
    private final PolicyCoverageRepository policyCoverageRepository = mock(PolicyCoverageRepository.class);
    private final BranchRepository branchRepository = mock(BranchRepository.class);

    private final PolicySynchronizer synchronizer = new PolicySynchronizer(
            insurerAdapter, policyRepository, policyCoverageRepository, coverageRepository, branchRepository);

    @Test
    void importsThePolicyFromTheInsurerDatabase() {
        givenRemotePolicy(LocalDate.now().minusMonths(6), LocalDate.now().plusMonths(6), "Daño accidental");
        Coverage coverage = coverage(3L, "Daño accidental");
        when(coverageRepository.findByName("Daño accidental")).thenReturn(Optional.of(coverage));
        savesWhatItIsGiven();

        Policy saved = synchronizer.importFromInsurer(POLICY_NUMBER, 1L);

        assertThat(saved.getExternalPolicyNumber()).isEqualTo(POLICY_NUMBER);
        assertThat(saved.getInsuredId()).isEqualTo(1L);
        assertThat(saved.isInForce()).isTrue();
        assertThat(saved.getSyncedAt()).isNotNull();
        // La suma asegurada NO está en la póliza: es de la cobertura contratada.
        PolicyCoverage contracted = firstContracted();
        assertThat(contracted.getCoverage()).isSameAs(coverage);
        assertThat(contracted.getSumInsured()).isEqualByComparingTo("90000");
        assertThat(contracted.getDisplayOrder()).isEqualTo(1);
    }

    /**
     * El bug que motivó todo esto: la compañía devuelve varias coberturas y el sync se quedaba con
     * {@code coverages.get(0)}. Una póliza de celulares cubre robo Y hurto, cada una con su suma
     * asegurada, y quedarse con una sola dejaba al asegurado sin poder denunciar lo que sí cubre.
     */
    @Test
    void importsEveryCoverageTheCompanyReturns() {
        givenRemotePolicyWithCoverages(
                coverageResponse("Robo de celular", "1300000"),
                coverageResponse("Hurto", "650000"));
        when(coverageRepository.findByName("Robo de celular")).thenReturn(Optional.of(coverage(1L, "Robo de celular")));
        when(coverageRepository.findByName("Hurto")).thenReturn(Optional.of(coverage(2L, "Hurto")));
        savesWhatItIsGiven();

        synchronizer.importFromInsurer(POLICY_NUMBER, 1L);

        List<PolicyCoverage> contracted = allContracted();
        assertThat(contracted).hasSize(2);
        assertThat(contracted).extracting(pc -> pc.getCoverage().getName())
                .containsExactly("Robo de celular", "Hurto");
        assertThat(contracted).extracting(PolicyCoverage::getSumInsured)
                .containsExactly(new BigDecimal("1300000"), new BigDecimal("650000"));
        // El orden de la compañía se conserva: es el desempate cuando la cobertura no se puede
        // resolver por el hecho generador.
        assertThat(contracted).extracting(PolicyCoverage::getDisplayOrder).containsExactly(1, 2);
    }

    /**
     * Una cobertura que el referente no configuró se saltea con warning en vez de voltear la
     * importación entera: que falte un riesgo no puede impedir denunciar contra los que sí están.
     */
    @Test
    void skipsTheCoveragesTheTenantHasNotConfigured() {
        givenRemotePolicyWithCoverages(
                coverageResponse("Robo de celular", "1300000"),
                coverageResponse("Hurto", "650000"));
        when(coverageRepository.findByName("Robo de celular")).thenReturn(Optional.of(coverage(1L, "Robo de celular")));
        when(coverageRepository.findByName("Hurto")).thenReturn(Optional.empty());
        savesWhatItIsGiven();

        synchronizer.importFromInsurer(POLICY_NUMBER, 1L);

        assertThat(allContracted()).extracting(pc -> pc.getCoverage().getName())
                .containsExactly("Robo de celular");
    }

    /** The snapshot's coverage window is "today", not the event date: that's the intake gate's job. */
    @Test
    void anExpiredPolicyIsImportedAsNotInForce() {
        givenRemotePolicy(LocalDate.now().minusYears(2), LocalDate.now().minusDays(1), "Robo de celular");
        when(coverageRepository.findByName("Robo de celular")).thenReturn(Optional.of(coverage(1L, "Robo de celular")));
        savesWhatItIsGiven();

        assertThat(synchronizer.importFromInsurer(POLICY_NUMBER, 1L).isInForce()).isFalse();
    }

    @Test
    void aPolicyTheInsurerDoesNotHaveEither_throws() {
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> synchronizer.importFromInsurer(POLICY_NUMBER, 1L))
                .isInstanceOf(UnresolvedCaseReferenceException.class)
                .hasMessageContaining("policy");
    }

    /**
     * The coverage is configured by the referente (deadlines, waiting period, events cap) and only
     * name and amount come from the company: creating one here would leave a coverage with no rule
     * at all. The error names the coverage and not the policy, which is what's really missing.
     */
    @Test
    void aCoverageTheTenantHasNotConfigured_throwsNamingTheCoverage() {
        givenRemotePolicy(LocalDate.now().minusMonths(6), LocalDate.now().plusMonths(6), "Daño accidental");
        when(coverageRepository.findByName("Daño accidental")).thenReturn(Optional.empty());
        savesWhatItIsGiven();

        assertThatThrownBy(() -> synchronizer.importFromInsurer(POLICY_NUMBER, 1L))
                .isInstanceOf(UnresolvedCaseReferenceException.class)
                .hasMessageContaining("Daño accidental");
    }

    /**
     * El nombre solo no alcanza para resolver una cobertura. La BD Aseguradora tiene los nombres
     * acotados a tres literales, así que una póliza de Tecnología Portátil trae su robo bajo el
     * nombre "Robo de celular": matcheando solo por nombre le colgaba a una notebook la cobertura
     * de Celulares, con sus plazos, su carencia y su tope de eventos.
     */
    @Test
    void doesNotAttachACoverageFromAnotherBranch() {
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.of(PolicyResponse.builder()
                .policyNumber(POLICY_NUMBER)
                .product("Seguro de Tecnología Portátil")
                .branch("Tecnología Portátil")
                .effectiveFrom(LocalDate.now().minusMonths(6).atStartOfDay())
                .effectiveTo(LocalDate.now().plusMonths(6).atStartOfDay())
                .coverages(List.of(coverageResponse("Robo de celular", "170000"),
                                   coverageResponse("Daño accidental", "90000")))
                .build()));
        when(branchRepository.findByName("Tecnología Portátil")).thenReturn(Optional.of(branch(2L)));
        when(coverageRepository.findByName("Robo de celular"))
                .thenReturn(Optional.of(coverage(1L, "Robo de celular", 1L)));
        when(coverageRepository.findByName("Daño accidental"))
                .thenReturn(Optional.of(coverage(3L, "Daño accidental", 2L)));
        savesWhatItIsGiven();

        synchronizer.importFromInsurer(POLICY_NUMBER, 1L);

        assertThat(allContracted()).extracting(pc -> pc.getCoverage().getName())
                .containsExactly("Daño accidental");
    }

    /**
     * El resync (decisión #10, la mitad "cron"): la compañía cambió la suma asegurada y la copia
     * local tiene que seguirla. Esa suma es el denominador del ratio de Fast Track, así que una
     * copia vieja hace que la pantalla del analista y el motor no coincidan.
     */
    @Test
    void resyncBringsTheLocalCopyBackInLineWithTheCompany() {
        givenRemotePolicyWithCoverages(coverageResponse("Hurto", "360000"));
        Coverage hurto = coverage(2L, "Hurto", 1L);
        when(coverageRepository.findByName("Hurto")).thenReturn(Optional.of(hurto));
        PolicyCoverage desactualizada = PolicyCoverage.builder()
                .id(7L).policyId(1L).coverage(hurto).displayOrder(1)
                .sumInsured(new BigDecimal("450000")).deductiblePct(new BigDecimal("10.00"))
                .build();
        when(policyCoverageRepository.findByPolicyIdAndCoverageId(1L, 2L))
                .thenReturn(Optional.of(desactualizada));
        when(policyCoverageRepository.findByPolicyIdOrderByDisplayOrderAsc(1L))
                .thenReturn(List.of(desactualizada));
        savesWhatItIsGiven();

        int cambios = synchronizer.resync(local());

        assertThat(cambios).isEqualTo(1);
        assertThat(desactualizada.getSumInsured()).isEqualByComparingTo("360000");
    }

    /** Sin diferencias no escribe nada: el caso normal de casi todas las corridas. */
    @Test
    void resyncOfAnAlreadyAlignedPolicyChangesNothing() {
        givenRemotePolicyWithCoverages(coverageResponse("Hurto", "360000"));
        Coverage hurto = coverage(2L, "Hurto", 1L);
        when(coverageRepository.findByName("Hurto")).thenReturn(Optional.of(hurto));
        PolicyCoverage alineada = PolicyCoverage.builder()
                .id(7L).policyId(1L).coverage(hurto).displayOrder(1)
                .sumInsured(new BigDecimal("360000.00")).deductiblePct(new BigDecimal("10.00"))
                .build();
        when(policyCoverageRepository.findByPolicyIdAndCoverageId(1L, 2L)).thenReturn(Optional.of(alineada));
        when(policyCoverageRepository.findByPolicyIdOrderByDisplayOrderAsc(1L)).thenReturn(List.of(alineada));
        savesWhatItIsGiven();

        assertThat(synchronizer.resync(local())).isZero();
        verify(policyCoverageRepository, never()).save(any(PolicyCoverage.class));
    }

    /**
     * Una cobertura que la compañía dejó de devolver NO se borra: hay expedientes abiertos
     * colgados de ella, y sacarla los dejaría apuntando a una cobertura que ya no existe.
     */
    @Test
    void resyncNeverDeletesACoverageTheCompanyStoppedReturning() {
        givenRemotePolicyWithCoverages(coverageResponse("Robo de celular", "900000"));
        Coverage robo = coverage(1L, "Robo de celular", 1L);
        Coverage hurto = coverage(2L, "Hurto", 1L);
        when(coverageRepository.findByName("Robo de celular")).thenReturn(Optional.of(robo));
        PolicyCoverage laQueYaNoEsta = PolicyCoverage.builder()
                .id(8L).policyId(1L).coverage(hurto).displayOrder(2)
                .sumInsured(new BigDecimal("450000")).deductiblePct(new BigDecimal("10.00"))
                .build();
        when(policyCoverageRepository.findByPolicyIdAndCoverageId(1L, 1L)).thenReturn(Optional.empty());
        when(policyCoverageRepository.findByPolicyIdOrderByDisplayOrderAsc(1L))
                .thenReturn(List.of(laQueYaNoEsta));
        savesWhatItIsGiven();

        synchronizer.resync(local());

        verify(policyCoverageRepository, never()).delete(any(PolicyCoverage.class));
        verify(policyCoverageRepository, never()).deleteByPolicyId(any());
    }

    /** La póliza que la compañía ya no tiene se deja como está, por la misma razón. */
    @Test
    void resyncOfAPolicyTheCompanyNoLongerHasChangesNothing() {
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.empty());

        assertThat(synchronizer.resync(local())).isZero();
        verify(policyRepository, never()).save(any(Policy.class));
    }

    private Policy local() {
        return Policy.builder()
                .id(1L)
                .insuredId(1L)
                .externalPolicyNumber(POLICY_NUMBER)
                .product("Celular Protegido Premium")
                .inForce(true)
                .build();
    }

    private Branch branch(Long id) {
        Branch branch = new Branch();
        branch.setId(id);
        return branch;
    }

    private void givenRemotePolicy(LocalDate from, LocalDate to, String coverageName) {
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.of(PolicyResponse.builder()
                .policyNumber(POLICY_NUMBER)
                .product("Seguro de Tecnología Portátil")
                .effectiveFrom(from == null ? null : from.atStartOfDay())
                .effectiveTo(to == null ? null : to.atStartOfDay())
                .coverages(List.of(coverageResponse(coverageName, "90000")))
                .build()));
    }

    private void givenRemotePolicyWithCoverages(PolicyResponse.Coverage... coverages) {
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.of(PolicyResponse.builder()
                .policyNumber(POLICY_NUMBER)
                .product("Celular Protegido Premium")
                .effectiveFrom(LocalDate.now().minusMonths(6).atStartOfDay())
                .effectiveTo(LocalDate.now().plusMonths(6).atStartOfDay())
                .coverages(List.of(coverages))
                .build()));
    }

    private PolicyResponse.Coverage coverageResponse(String name, String sumInsured) {
        return PolicyResponse.Coverage.builder()
                .code("COB-" + name)
                .description(name)
                .insuredAmount(new BigDecimal(sumInsured))
                .deductiblePct(new BigDecimal("10.00"))
                .build();
    }

    private void savesWhatItIsGiven() {
        when(policyRepository.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));
        when(policyCoverageRepository.save(any(PolicyCoverage.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private List<PolicyCoverage> allContracted() {
        ArgumentCaptor<PolicyCoverage> captor = ArgumentCaptor.forClass(PolicyCoverage.class);
        verify(policyCoverageRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private PolicyCoverage firstContracted() {
        return allContracted().getFirst();
    }

    private Coverage coverage(Long id, String name) {
        return coverage(id, name, null);
    }

    private Coverage coverage(Long id, String name, Long branchId) {
        Coverage coverage = new Coverage();
        coverage.setId(id);
        coverage.setName(name);
        coverage.setBranchId(branchId);
        return coverage;
    }
}
