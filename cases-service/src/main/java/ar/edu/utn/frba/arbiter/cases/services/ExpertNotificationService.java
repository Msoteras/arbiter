package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;
import ar.edu.utn.frba.arbiter.cases.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.common.email.SendGridAdapter;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Emails the external expert the case they have to verify. The send is recorded on
 * {@code expert_assessment.notified_at}, not as a {@code notification} row: the expert isn't a {@code User}.
 *
 * <p>The email carries the case, never the analysis (classification, risk score, model reasons):
 * telling the expert what the system suspects would hand them the conclusion before they look.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExpertNotificationService {

    /**
     * SendGrid rejects the whole message past 30 MB including base64 overhead (~33%), so it's better
     * to send fewer files and name the ones left out.
     */
    private static final long MAX_ATTACHMENT_BYTES = 18L * 1024 * 1024;

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private static final Locale AR = Locale.forLanguageTag("es-AR");

    private static final Set<String> INTERNAL_REPORT_TYPES = Set.of(
            ExpertAssessmentService.REPORT_DOCUMENT_TYPE, ExpertAssessmentService.REPAIR_DOCUMENT_TYPE);

    private final SendGridAdapter sendGridAdapter;
    private final CaseDocumentRepository caseDocumentRepository;
    private final BranchRepository branchRepository;

    /**
     * Best-effort: a delivery failure must not undo the referral. Returns null when nothing went
     * out, which is what tells the analyst nobody was actually asked.
     */
    public Instant notifyDerivation(Case caseRecord, ExpertAssessment assessment) {
        try {
            boolean repair = assessment.getProviderType() == ProviderType.SERVICIO_TECNICO;
            // A repair shop fixes the item, it doesn't verify the claim: the insured's paperwork stays in.
            List<SendGridAdapter.Attachment> attachments =
                    repair ? List.of() : attachmentsOf(caseRecord.getId());
            boolean sent = sendGridAdapter.send(
                    assessment.getExpertEmail(),
                    (repair ? "Solicitud de reparación" : "Solicitud de peritaje")
                            + " · Siniestro #" + caseRecord.getId(),
                    repair ? repairBody(caseRecord, assessment) : body(caseRecord, assessment, attachments),
                    attachments);
            // With no API key the adapter returns without sending; that must not read as notified.
            return sent ? Instant.now() : null;
        } catch (Exception | LinkageError e) {
            // LinkageError too: a missing mail SDK surfaces as NoClassDefFoundError, not an Exception.
            log.error("Could not email the expert assessment for case {}", caseRecord.getId(), e);
            return null;
        }
    }

    /**
     * Oldest first up to the size budget, so a heavy case drops its tail rather than an arbitrary
     * pick; every dropped file is logged.
     */
    private List<SendGridAdapter.Attachment> attachmentsOf(Long caseId) {
        List<SendGridAdapter.Attachment> attachments = new ArrayList<>();
        long budget = MAX_ATTACHMENT_BYTES;
        for (CaseDocument document : caseDocumentRepository.findByCaseId(caseId).stream()
                // Another provider's report is our evidence, not part of the claim they get sent.
                .filter(document -> !INTERNAL_REPORT_TYPES.contains(document.getType()))
                .sorted(Comparator.comparing(CaseDocument::getId))
                .toList()) {
            byte[] content = document.getContent();
            if (content == null || content.length > budget) {
                log.warn("[ExpertNotification] Case {}: document {} left out of the email ({} bytes)",
                        caseId, document.getFilename(), content == null ? 0 : content.length);
                continue;
            }
            budget -= content.length;
            attachments.add(new SendGridAdapter.Attachment(
                    document.getFilename(), document.getContentType(), content));
        }
        return attachments;
    }

    private String body(Case caseRecord, ExpertAssessment assessment,
                        List<SendGridAdapter.Attachment> attachments) {
        return """
                <p>Hola,</p>
                <p>Les derivamos el siniestro <strong>#%d</strong> para su verificación.</p>
                <h3>Resumen del siniestro</h3>
                <ul>
                  <li><strong>N° de póliza:</strong> %s</li>
                  <li><strong>Ramo:</strong> %s</li>
                  <li><strong>Producto:</strong> %s</li>
                  <li><strong>Cobertura:</strong> %s</li>
                  <li><strong>Asegurado:</strong> %s (DNI %s)</li>
                  <li><strong>Causa denunciada:</strong> %s</li>
                  <li><strong>Bien declarado:</strong> %s</li>
                  <li><strong>Importe reclamado:</strong> %s</li>
                  <li><strong>Fecha y hora de ocurrencia:</strong> %s</li>
                  <li><strong>Fecha de denuncia:</strong> %s</li>
                  <li><strong>Lugar:</strong> %s</li>
                </ul>
                <p><strong>Motivo de la derivación:</strong> %s</p>
                <p><strong>Descripción de la denuncia:</strong><br>%s</p>
                <p><strong>Documentación adjunta:</strong> %s</p>
                <p>Al finalizar, envíennos el informe con su conclusión respondiendo a este correo.</p>
                <p>Arbiter</p>
                """.formatted(
                caseRecord.getId(),
                nullSafe(caseRecord.getPolicy() != null
                        ? caseRecord.getPolicy().getExternalPolicyNumber() : null),
                branchName(caseRecord),
                nullSafe(caseRecord.getPolicy() != null ? caseRecord.getPolicy().getProduct() : null),
                nullSafe(caseRecord.getCoverage() != null ? caseRecord.getCoverage().getName() : null),
                insuredName(caseRecord),
                nullSafe(caseRecord.getInsured() != null ? caseRecord.getInsured().getDni() : null),
                caseRecord.getClaimCause().getName(),
                nullSafe(caseRecord.getDeclaredItem()),
                amount(caseRecord.getClaimedAmount()),
                occurredAt(caseRecord),
                caseRecord.getReportedAt() != null ? DATE_TIME.format(caseRecord.getReportedAt()) : "—",
                nullSafe(caseRecord.getEventAddress()),
                assessment.getReason(),
                nullSafe(caseRecord.getDescription()),
                attachmentList(attachments));
    }

    /**
     * Only what a repair shop needs. No name, DNI, address or narrative (personal data, Ley 25.326),
     * and no claimed amount, which whoever quotes the repair would otherwise read.
     */
    private String repairBody(Case caseRecord, ExpertAssessment assessment) {
        return """
                <p>Hola,</p>
                <p>Les derivamos el siniestro <strong>#%d</strong> para su reparación o cotización.</p>
                <ul>
                  <li><strong>Ramo:</strong> %s</li>
                  <li><strong>Bien declarado:</strong> %s</li>
                  <li><strong>Fecha y hora de ocurrencia:</strong> %s</li>
                </ul>
                <p><strong>Motivo de la derivación:</strong> %s</p>
                <p>Al finalizar, envíennos el resultado (reparado, irreparable o presupuesto)
                respondiendo a este correo.</p>
                <p>Arbiter</p>
                """.formatted(
                caseRecord.getId(),
                branchName(caseRecord),
                nullSafe(caseRecord.getDeclaredItem()),
                occurredAt(caseRecord),
                assessment.getReason());
    }

    private String occurredAt(Case caseRecord) {
        return caseRecord.getOccurredAt() == null ? "—"
                : DATE_TIME.format(caseRecord.getOccurredAt().atZone(ZoneId.systemDefault()));
    }

    /** Named, not counted: the expert can tell a file that got dropped from one never uploaded. */
    private String attachmentList(List<SendGridAdapter.Attachment> attachments) {
        return attachments.isEmpty()
                ? "el expediente no tiene documentación cargada"
                : String.join(", ", attachments.stream()
                        .map(SendGridAdapter.Attachment::filename)
                        .toList());
    }

    private String branchName(Case caseRecord) {
        if (caseRecord.getCoverage() == null || caseRecord.getCoverage().getBranchId() == null) {
            return "—";
        }
        return branchRepository.findById(caseRecord.getCoverage().getBranchId())
                .map(Branch::getName)
                .orElse("—");
    }

    private String insuredName(Case caseRecord) {
        if (caseRecord.getInsured() == null) {
            return "—";
        }
        return caseRecord.getInsured().getName() + " " + caseRecord.getInsured().getSurname();
    }

    private String amount(BigDecimal value) {
        return value == null ? "—" : NumberFormat.getCurrencyInstance(AR).format(value);
    }

    private String nullSafe(String value) {
        return value != null ? value : "—";
    }
}
