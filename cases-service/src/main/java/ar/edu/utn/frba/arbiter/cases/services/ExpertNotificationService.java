package ar.edu.utn.frba.arbiter.cases.services;

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

/**
 * Emails the external expert the case they have to verify.
 *
 * <p>Separate from {@link CaseNotificationService}, which writes a {@code notification} row per
 * message: that table's recipient is a {@code User}, and the expert deliberately isn't one. The
 * record of this send is {@code expert_assessment.notified_at} instead.
 *
 * <p>What goes in the email is the case, not the analysis: no classification, no risk score, no
 * reasons the model gave. The expert is asked to verify facts — telling them what the system
 * already suspects would be handing them the conclusion before they look. Inside that limit they
 * get everything: the same summary the analyst reads, plus the documentation on file, because an
 * expert who has to ask for the police report by reply loses a day on every case.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExpertNotificationService {

    /**
     * What the attachments may add up to. SendGrid rejects the whole message past 30 MB (base64
     * included, ~33% over the raw bytes), and a rejected message means the expert gets nothing —
     * better to send the case with fewer files and name the ones left out.
     */
    private static final long MAX_ATTACHMENT_BYTES = 18L * 1024 * 1024;

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private static final Locale AR = Locale.forLanguageTag("es-AR");

    private final SendGridAdapter sendGridAdapter;
    private final CaseDocumentRepository caseDocumentRepository;
    private final BranchRepository branchRepository;

    /**
     * Best-effort, like every other notification: a delivery failure must not undo a derivation
     * that already happened. Returns when the message went out, or null if it didn't — the
     * caller stores it, and a null is what tells the analyst nobody was actually asked.
     */
    public Instant notifyDerivation(Case caseRecord, ExpertAssessment assessment) {
        try {
            List<SendGridAdapter.Attachment> attachments = attachmentsOf(caseRecord.getId());
            boolean sent = sendGridAdapter.send(
                    assessment.getExpertEmail(),
                    "Solicitud de peritaje · Siniestro #" + caseRecord.getId(),
                    body(caseRecord, assessment, attachments),
                    attachments);
            // Not `Instant.now()` unconditionally: with no API key the adapter logs and returns
            // without sending, and stamping that as notified told the analyst the expert had been
            // asked when nobody had.
            return sent ? Instant.now() : null;
        } catch (Exception | LinkageError e) {
            // LinkageError too: a missing mail SDK surfaces as NoClassDefFoundError, which is not
            // an Exception — same trap CaseNotificationService already fell into once.
            log.error("Could not email the expert assessment for case {}", caseRecord.getId(), e);
            return null;
        }
    }

    /**
     * Everything on file, oldest first, up to the size budget. Ordered by id so that what a
     * heavy case drops is its tail and not an arbitrary pick, and logged when it happens: sending
     * five documents out of seven without saying so reads as "these are all of them".
     */
    private List<SendGridAdapter.Attachment> attachmentsOf(Long caseId) {
        List<SendGridAdapter.Attachment> attachments = new ArrayList<>();
        long budget = MAX_ATTACHMENT_BYTES;
        for (CaseDocument document : caseDocumentRepository.findByCaseId(caseId).stream()
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
                caseRecord.getOccurredAt() != null
                        ? DATE_TIME.format(caseRecord.getOccurredAt().atZone(ZoneId.systemDefault()))
                        : "—",
                caseRecord.getReportedAt() != null ? DATE_TIME.format(caseRecord.getReportedAt()) : "—",
                nullSafe(caseRecord.getEventAddress()),
                assessment.getReason(),
                nullSafe(caseRecord.getDescription()),
                attachmentList(attachments));
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
