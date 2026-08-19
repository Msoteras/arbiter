package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.rules.models.repositories.CoverageRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The engine only has the {@code coverageId} and the claim-cause name; the schedule is stored by
 * branch + claim cause. The service resolves coverage → branch and returns that claim cause's
 * schedule.
 *
 * <p>What it answers with when there's nothing to return is the whole point: an <b>empty list</b>
 * means "needs no documents" and {@code null} means "couldn't resolve it". The engine only falls
 * back to its baseline on the second — folding both into an empty list made a referente who cleared
 * every document from the panel still get the baseline's.
 */
class InternalDocumentRequirementServiceTest {

    private final CoverageRepository coverageRepository = mock(CoverageRepository.class);
    private final DocumentRequirementService documentRequirements = mock(DocumentRequirementService.class);
    private final InternalDocumentRequirementService service =
            new InternalDocumentRequirementService(coverageRepository, documentRequirements);

    @Test
    void resolvesCoverageToBranchAndReturnsItsAgenda() {
        coverageBelongsToBranch(10L, 2L);
        when(documentRequirements.findByBranchIdAndClaimCauseName(2L, "Hurto"))
                .thenReturn(Optional.of(List.of("police_report", "imei_deregistration")));

        assertThat(service.getByCoverage(10L, "Hurto")).containsExactly("police_report", "imei_deregistration");
    }

    /** The case that was broken: the referente cleared them all, and that has to reach the engine. */
    @Test
    void returnsEmptyListWhenTheClaimCauseNeedsNoDocuments() {
        coverageBelongsToBranch(10L, 2L);
        when(documentRequirements.findByBranchIdAndClaimCauseName(2L, "Caída"))
                .thenReturn(Optional.of(List.of()));

        assertThat(service.getByCoverage(10L, "Caída")).isEmpty();
    }

    @Test
    void returnsNullWhenCoverageDoesNotExist() {
        when(coverageRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.getByCoverage(99L, "Hurto")).isNull();
    }

    @Test
    void returnsNullWhenTheBranchHasNoSuchClaimCause() {
        coverageBelongsToBranch(10L, 2L);
        when(documentRequirements.findByBranchIdAndClaimCauseName(2L, "Inexistente"))
                .thenReturn(Optional.empty());

        assertThat(service.getByCoverage(10L, "Inexistente")).isNull();
    }

    private void coverageBelongsToBranch(Long coverageId, Long branchId) {
        Coverage coverage = mock(Coverage.class);
        when(coverage.getBranchId()).thenReturn(branchId);
        when(coverageRepository.findById(coverageId)).thenReturn(Optional.of(coverage));
    }
}
