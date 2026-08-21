package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.rules.dto.CatalogOption;
import ar.edu.utn.frba.arbiter.rules.exceptions.BranchInUseException;
import ar.edu.utn.frba.arbiter.rules.exceptions.BranchNameConflictException;
import ar.edu.utn.frba.arbiter.rules.exceptions.BranchNotFoundException;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ClaimCauseRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Global branch catalog CRUD: name uniqueness, not-found and the delete guard. Plain Mockito. */
class BranchCatalogServiceTest {

    private final BranchRepository branchRepository = mock(BranchRepository.class);
    private final ClaimCauseRepository claimCauseRepository = mock(ClaimCauseRepository.class);
    private final BranchCatalogService service = new BranchCatalogService(branchRepository, claimCauseRepository);

    @Test
    void createSavesWhenNameIsFree() {
        when(branchRepository.findByName("Hogar")).thenReturn(Optional.empty());
        when(branchRepository.save(any(Branch.class))).thenAnswer(inv -> {
            Branch b = inv.getArgument(0);
            b.setId(5L);
            return b;
        });

        assertThat(service.create("  Hogar ")).isEqualTo(new CatalogOption(5L, "Hogar"));
    }

    @Test
    void createRejectsDuplicateName() {
        when(branchRepository.findByName("Celulares")).thenReturn(Optional.of(Branch.builder().id(1L).name("Celulares").build()));

        assertThatThrownBy(() -> service.create("Celulares")).isInstanceOf(BranchNameConflictException.class);
        verify(branchRepository, never()).save(any());
    }

    @Test
    void createRejectsBlankName() {
        assertThatThrownBy(() -> service.create("   ")).isInstanceOf(InvalidRuleConfigurationException.class);
    }

    @Test
    void renameFailsWhenBranchMissing() {
        when(branchRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rename(9L, "Nuevo")).isInstanceOf(BranchNotFoundException.class);
    }

    @Test
    void renameRejectsNameTakenByAnotherBranch() {
        when(branchRepository.findById(1L)).thenReturn(Optional.of(Branch.builder().id(1L).name("Celulares").build()));
        when(branchRepository.findByName("Hogar")).thenReturn(Optional.of(Branch.builder().id(2L).name("Hogar").build()));

        assertThatThrownBy(() -> service.rename(1L, "Hogar")).isInstanceOf(BranchNameConflictException.class);
    }

    @Test
    void deleteRejectsBranchWithClaimCauses() {
        when(branchRepository.findById(1L)).thenReturn(Optional.of(Branch.builder().id(1L).name("Celulares").build()));
        when(claimCauseRepository.findByBranch_IdOrderByNameAsc(1L)).thenReturn(List.of(mock(ClaimCause.class)));

        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(BranchInUseException.class);
        verify(branchRepository, never()).delete(any());
    }

    @Test
    void deleteRemovesUnusedBranch() {
        Branch branch = Branch.builder().id(7L).name("Vacío").build();
        when(branchRepository.findById(7L)).thenReturn(Optional.of(branch));
        when(claimCauseRepository.findByBranch_IdOrderByNameAsc(7L)).thenReturn(List.of());

        service.delete(7L);

        verify(branchRepository).delete(branch);
    }
}
