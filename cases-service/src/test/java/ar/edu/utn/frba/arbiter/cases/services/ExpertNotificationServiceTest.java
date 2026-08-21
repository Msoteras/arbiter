package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;
import ar.edu.utn.frba.arbiter.cases.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.common.email.SendGridAdapter;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpertNotificationServiceTest {

    private static final String EXPERT_EMAIL = "perito@example.com";

    @Mock
    private SendGridAdapter sendGridAdapter;

    @Mock
    private CaseDocumentRepository caseDocumentRepository;

    @Mock
    private BranchRepository branchRepository;

    @InjectMocks
    private ExpertNotificationService service;

    @Test
    void notifyDerivation_stampsWhenTheEmailWentOut() {
        when(sendGridAdapter.send(anyString(), anyString(), anyString(), anyList())).thenReturn(true);

        Instant notifiedAt = service.notifyDerivation(caseRecord(), assessment());

        assertThat(notifiedAt).isNotNull();
        verify(sendGridAdapter).send(eq(EXPERT_EMAIL), contains("#42"), anyString(), anyList());
    }

    /**
     * Regression: with no API key the adapter logs and returns without sending, and this used to
     * stamp notifiedAt anyway — the analyst read "notificado" on an expert nobody had asked.
     */
    @Test
    void notifyDerivation_doesNotStampWhenNothingWentOut() {
        when(sendGridAdapter.send(anyString(), anyString(), anyString(), anyList())).thenReturn(false);

        assertThat(service.notifyDerivation(caseRecord(), assessment())).isNull();
    }

    /**
     * El perito recibe la documentación con la solicitud. Pedirla por mail aparte le cuesta un día
     * por expediente, y el cuerpo los nombra para que note si falta alguno.
     */
    @Test
    void notifyDerivation_attachesTheCaseDocumentsAndNamesThem() {
        when(caseDocumentRepository.findByCaseId(42L)).thenReturn(List.of(
                document(1L, "denuncia-policial.pdf", "application/pdf"),
                document(2L, "foto-del-bien.jpg", "image/jpeg")));
        when(sendGridAdapter.send(anyString(), anyString(), anyString(), anyList())).thenReturn(true);

        service.notifyDerivation(caseRecord(), assessment());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<SendGridAdapter.Attachment>> attachments = ArgumentCaptor.forClass(List.class);
        verify(sendGridAdapter).send(eq(EXPERT_EMAIL), anyString(), body.capture(), attachments.capture());

        assertThat(attachments.getValue()).extracting(SendGridAdapter.Attachment::filename)
                .containsExactly("denuncia-policial.pdf", "foto-del-bien.jpg");
        assertThat(body.getValue())
                .contains("Resumen del siniestro")
                .contains("denuncia-policial.pdf")
                .contains("foto-del-bien.jpg");
    }

    /** Best-effort by contract: a delivery failure must not undo a derivation that happened. */
    @Test
    void notifyDerivation_swallowsTheFailureAndReportsItAsNotNotified() {
        when(sendGridAdapter.send(anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new RuntimeException("SendGrid caído"));

        assertThatCode(() -> assertThat(service.notifyDerivation(caseRecord(), assessment())).isNull())
                .doesNotThrowAnyException();
    }

    private Case caseRecord() {
        ClaimCause cause = new ClaimCause();
        cause.setName("Robo en vía pública");
        Case caseRecord = new Case();
        caseRecord.setId(42L);
        caseRecord.setClaimCause(cause);
        caseRecord.setOccurredAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        caseRecord.setEventAddress("Av. Corrientes 1234");
        caseRecord.setDescription("Me robaron el celular");
        return caseRecord;
    }

    private CaseDocument document(Long id, String filename, String contentType) {
        return CaseDocument.builder()
                .id(id)
                .caseId(42L)
                .type("police_report")
                .filename(filename)
                .contentType(contentType)
                .content(new byte[] {1, 2, 3})
                .build();
    }

    private ExpertAssessment assessment() {
        return ExpertAssessment.builder()
                .caseId(42L)
                .expertName("Estudio Verifica S.R.L.")
                .expertEmail(EXPERT_EMAIL)
                .reason("Monto alto")
                .build();
    }
}
