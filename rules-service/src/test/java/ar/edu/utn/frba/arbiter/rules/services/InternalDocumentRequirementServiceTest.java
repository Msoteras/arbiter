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
 * schedule; with no coverage or no branch, an empty list (the engine composes over its baseline and
 * never falls over missing config).
 */
class InternalDocumentRequirementServiceTest {

    private final CoverageRepository coverageRepository = mock(CoverageRepository.class);
    private final DocumentRequirementService documentRequirements = mock(DocumentRequirementService.class);
    private final InternalDocumentRequirementService service =
            new InternalDocumentRequirementService(coverageRepository, documentRequirements);

    @Test
    void resolvesCoverageToBranchAndReturnsItsAgenda() {
        Coverage coverage = mock(Coverage.class);
        when(coverage.getBranchId()).thenReturn(2L);
        when(coverageRepository.findById(10L)).thenReturn(Optional.of(coverage));
        when(documentRequirements.getByBranchIdAndClaimCauseName(2L, "Hurto"))
                .thenReturn(List.of("police_report", "imei_deregistration"));

        assertThat(service.getByCoverage(10L, "Hurto")).containsExactly("police_report", "imei_deregistration");
    }

    @Test
    void returnsEmptyWhenCoverageDoesNotExist() {
        when(coverageRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.getByCoverage(99L, "Hurto")).isEmpty();
    }
}
