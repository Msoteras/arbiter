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
 * El motor solo tiene el {@code coverageId}; la agenda se guarda por ramo. El servicio cruza
 * cobertura → ramo y devuelve la agenda de ese ramo; sin cobertura o sin ramo, lista vacía (el
 * motor compone sobre su baseline y nunca se cae por falta de config).
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
        when(documentRequirements.get(2L)).thenReturn(List.of("police_report", "imei_deregistration"));

        assertThat(service.getByCoverage(10L)).containsExactly("police_report", "imei_deregistration");
    }

    @Test
    void returnsEmptyWhenCoverageDoesNotExist() {
        when(coverageRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.getByCoverage(99L)).isEmpty();
    }
}
