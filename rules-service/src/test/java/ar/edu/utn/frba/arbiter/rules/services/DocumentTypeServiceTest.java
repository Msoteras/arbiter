package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.dto.DocumentTypeResponse;
import ar.edu.utn.frba.arbiter.common.models.entities.DocumentType;
import ar.edu.utn.frba.arbiter.rules.exceptions.BranchNotFoundException;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchDocumentTypeRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.DocumentTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Which documents a branch may ask for, and the rejection of any other. Plain Mockito. */
class DocumentTypeServiceTest {

    private static final DocumentType POLICE_REPORT = type(1L, "police_report", "Denuncia policial");
    private static final DocumentType PURCHASE_PROOF = type(2L, "purchase_proof", "Comprobante de compra");

    private final DocumentTypeRepository documentTypeRepository = mock(DocumentTypeRepository.class);
    private final BranchDocumentTypeRepository branchDocumentTypeRepository = mock(BranchDocumentTypeRepository.class);
    private final BranchRepository branchRepository = mock(BranchRepository.class);
    private final DocumentTypeService service =
            new DocumentTypeService(documentTypeRepository, branchDocumentTypeRepository, branchRepository);

    @BeforeEach
    void branchTwoAsksForPoliceReportAndPurchaseProof() {
        when(branchRepository.existsById(2L)).thenReturn(true);
        when(branchDocumentTypeRepository.findDocumentTypesByBranchId(2L))
                .thenReturn(List.of(PURCHASE_PROOF, POLICE_REPORT));
    }

    @Test
    void listsTheBranchDocumentsWithTheirNames() {
        assertThat(service.listForBranch(2L)).containsExactly(
                new DocumentTypeResponse("purchase_proof", "Comprobante de compra"),
                new DocumentTypeResponse("police_report", "Denuncia policial"));
    }

    @Test
    void anUnknownBranchIsNotFoundRatherThanEmpty() {
        assertThatThrownBy(() -> service.listForBranch(9L)).isInstanceOf(BranchNotFoundException.class);
    }

    @Test
    void returnsTheBranchRowsInTheOrderGivenWithoutRepeats() {
        assertThat(service.requireOfBranch(2L, List.of("police_report", "purchase_proof", "police_report")))
                .containsExactly(POLICE_REPORT, PURCHASE_PROOF);
    }

    /** Saving the rest would drop the foreign document without the referente noticing. */
    @Test
    void rejectsTheWholeListWhenOneDocumentIsNotTheBranchs() {
        assertThatThrownBy(() -> service.requireOfBranch(2L, List.of("police_report", "imei_deregistration")))
                .isInstanceOf(InvalidRuleConfigurationException.class)
                .hasMessageContaining("imei_deregistration")
                .hasMessageNotContaining("police_report");
    }

    private static DocumentType type(Long id, String code, String name) {
        return DocumentType.builder().id(id).code(code).name(name).build();
    }
}
