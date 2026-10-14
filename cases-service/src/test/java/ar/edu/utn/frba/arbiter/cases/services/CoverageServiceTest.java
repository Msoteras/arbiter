package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CoverageRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Branch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** The names the referent picks a coverage name from, read off the insurer's policies. */
@ExtendWith(MockitoExtension.class)
class CoverageServiceTest {

    @Mock private CoverageRepository coverageRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private InsurerAdapter insurerAdapter;

    @InjectMocks private CoverageService coverageService;

    /** A renamed branch still answers to the insurer database's name for it. */
    @Test
    void insurerCoverageNames_looksUpTheBranchByTheInsurerDatabasesName() {
        when(branchRepository.findById(1L)).thenReturn(Optional.of(
                Branch.builder().id(1L).name("Teléfonos").externalName("Celulares").build()));
        when(insurerAdapter.findCoverageNames("Celulares"))
                .thenReturn(List.of("Daño accidental", "Robo de celular"));

        assertThat(coverageService.insurerCoverageNames(1L))
                .containsExactly("Daño accidental", "Robo de celular");
    }

    @Test
    void insurerCoverageNames_withoutAnExternalName_usesTheBranchName() {
        when(branchRepository.findById(1L)).thenReturn(Optional.of(
                Branch.builder().id(1L).name("Celulares").build()));
        when(insurerAdapter.findCoverageNames("Celulares")).thenReturn(List.of("Hurto"));

        assertThat(coverageService.insurerCoverageNames(1L)).containsExactly("Hurto");
    }

    @Test
    void insurerCoverageNames_unknownBranch_throws() {
        when(branchRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> coverageService.insurerCoverageNames(9L))
                .isInstanceOf(UnresolvedCaseReferenceException.class);
    }
}
