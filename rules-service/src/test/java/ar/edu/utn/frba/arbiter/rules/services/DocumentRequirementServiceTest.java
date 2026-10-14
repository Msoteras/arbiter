package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.DocumentType;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimCause;
import ar.edu.utn.frba.arbiter.rules.dto.DocumentRequirementDto;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.DocumentRequirement;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.DocumentRequirementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The schedule stores catalog rows, answers with their codes, and refuses a document foreign to the branch. */
class DocumentRequirementServiceTest {

    private static final Branch BRANCH = Branch.builder().id(2L).name("Tecnología Portátil").build();
    private static final ClaimCause THEFT = ClaimCause.builder().id(7L).name("Robo en vía pública").branch(BRANCH).build();
    private static final DocumentType POLICE_REPORT =
            DocumentType.builder().id(1L).code("police_report").name("Denuncia policial").build();

    private final DocumentRequirementRepository requirementRepository = mock(DocumentRequirementRepository.class);
    private final ClaimCauseRepository claimCauseRepository = mock(ClaimCauseRepository.class);
    private final BranchRepository branchRepository = mock(BranchRepository.class);
    private final DocumentTypeService documentTypes = mock(DocumentTypeService.class);
    private final DocumentRequirementService service = new DocumentRequirementService(
            requirementRepository, claimCauseRepository, branchRepository, documentTypes);

    @BeforeEach
    void theftBelongsToTheBranch() {
        when(branchRepository.findById(2L)).thenReturn(Optional.of(BRANCH));
        when(claimCauseRepository.findById(7L)).thenReturn(Optional.of(THEFT));
    }

    @Test
    void readsTheScheduleAsCodes() {
        when(requirementRepository.findByBranch_IdAndClaimCause_Id(2L, 7L)).thenReturn(List.of(
                DocumentRequirement.builder().documentType(POLICE_REPORT).branch(BRANCH).claimCause(THEFT).build()));

        assertThat(service.get(2L, 7L)).containsExactly("police_report");
    }

    @Test
    void savesTheCatalogRowsAndAnswersWithTheirCodes() {
        when(documentTypes.requireOfBranch(2L, List.of("police_report"))).thenReturn(List.of(POLICE_REPORT));
        when(requirementRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<DocumentRequirementDto> saved = service.upsert(2L, 7L, List.of("police_report"));

        assertThat(saved).extracting(DocumentRequirementDto::documentType).containsExactly("police_report");
    }

    /** The previous schedule must survive a rejected save. */
    @Test
    void aDocumentForeignToTheBranchLeavesTheScheduleUntouched() {
        when(documentTypes.requireOfBranch(2L, List.of("imei_deregistration")))
                .thenThrow(new InvalidRuleConfigurationException("El ramo no admite estos documentos: imei_deregistration."));

        assertThatThrownBy(() -> service.upsert(2L, 7L, List.of("imei_deregistration")))
                .isInstanceOf(InvalidRuleConfigurationException.class);
        verify(requirementRepository, never()).deleteByBranch_IdAndClaimCause_Id(anyLong(), anyLong());
    }
}
