package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;
import ar.edu.utn.frba.arbiter.common.email.SendGridAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Emails the external expert the case they have to verify.
 *
 * <p>Separate from {@link CaseNotificationService}, which writes a {@code notification} row per
 * message: that table's recipient is a {@code User}, and the expert deliberately isn't one. The
 * record of this send is {@code expert_assessment.notified_at} instead.
 *
 * <p>What goes in the email is the case, not the analysis: no classification, no risk score, no
 * reasons the model gave. The expert is asked to verify facts — telling them what the system
 * already suspects would be handing them the conclusion before they look.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExpertNotificationService {

    private final SendGridAdapter sendGridAdapter;

    /**
     * Best-effort, like every other notification: a delivery failure must not undo a derivation
     * that already happened. Returns when the message went out, or null if it didn't — the
     * caller stores it, and a null is what tells the analyst nobody was actually asked.
     */
    public Instant notifyDerivation(Case caseRecord, ExpertAssessment assessment) {
        try {
            sendGridAdapter.send(
                    assessment.getExpertEmail(),
                    "Solicitud de peritaje · Siniestro #" + caseRecord.getId(),
                    body(caseRecord, assessment));
            return Instant.now();
        } catch (Exception | LinkageError e) {
            // LinkageError too: a missing mail SDK surfaces as NoClassDefFoundError, which is not
            // an Exception — same trap CaseNotificationService already fell into once.
            log.error("Could not email the expert assessment for case {}", caseRecord.getId(), e);
            return null;
        }
    }

    private String body(Case caseRecord, ExpertAssessment assessment) {
        return """
                <p>Hola,</p>
                <p>Les derivamos el siniestro <strong>#%d</strong> para su verificación.</p>
                <ul>
                  <li><strong>Causa denunciada:</strong> %s</li>
                  <li><strong>Bien declarado:</strong> %s</li>
                  <li><strong>Fecha del hecho:</strong> %s</li>
                  <li><strong>Lugar:</strong> %s</li>
                </ul>
                <p><strong>Motivo de la derivación:</strong> %s</p>
                <p><strong>Descripción de la denuncia:</strong><br>%s</p>
                <p>Al finalizar, envíennos el informe con su conclusión respondiendo a este correo.</p>
                <p>Arbiter</p>
                """.formatted(
                caseRecord.getId(),
                caseRecord.getClaimCause().getName(),
                nullSafe(caseRecord.getDeclaredItem()),
                caseRecord.getOccurredAt(),
                nullSafe(caseRecord.getEventAddress()),
                assessment.getReason(),
                nullSafe(caseRecord.getDescription()));
    }

    private String nullSafe(String value) {
        return value != null ? value : "—";
    }
}
