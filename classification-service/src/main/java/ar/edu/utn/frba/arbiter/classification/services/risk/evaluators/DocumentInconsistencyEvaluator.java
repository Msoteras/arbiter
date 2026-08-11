package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Contradicciones entre lo que dicen los documentos adjuntos y lo que dice el siniestro (H0012).
 *
 * <p>Fue un stub hasta el 10/08 porque el OCR devolvía <b>texto libre</b>: comparar párrafos no da
 * un resultado determinístico, y este factor tiene que serlo. Lo que lo destrabó fue que la pasada
 * de extracción empezara a devolver los datos como <b>campos tipados</b>
 * ({@link DocumentExtraction.Fields}) — el modelo lee, el código compara (D4b, mismo patrón que D4a).
 *
 * <p><b>Un campo ausente nunca es una inconsistencia.</b> Una constancia policial no trae IMEI y una
 * foto del equipo no trae monto: null significa "el documento no lo dice", y confundirlo con "no
 * coincide" convertiría cada adjunto incompleto en una sospecha de fraude.
 *
 * <p>Sin documentos examinados el factor se declara <b>no evaluable</b> en vez de aportar 0.0: un
 * expediente sin adjuntos analizados no es un expediente consistente, es uno del que no sabemos
 * nada, y un 0.0 le bajaría el score a todo el mundo.
 */
@Component
public class DocumentInconsistencyEvaluator implements RiskFactorEvaluator {

    /** Diferencia tolerada entre el monto del documento y el reclamado: redondeos, IVA, envío. */
    private static final BigDecimal AMOUNT_TOLERANCE_RATIO = new BigDecimal("0.10");

    /** Un documento fechado más de una semana antes del hecho ya no es "del hecho". */
    private static final int DOCUMENT_DATE_TOLERANCE_DAYS = 7;

    /** Cada contradicción encontrada suma esto; con dos el factor ya satura. */
    private static final double SCORE_PER_FINDING = 0.5;

    /** Tipo de adjunto de la constancia policial, el mismo que usa la agenda documental. */
    private static final String POLICE_REPORT_TYPE = "police_report";

    @Override
    public String factorId() {
        return RiskFactorIds.DOCUMENT_INCONSISTENCY;
    }

    @Override
    public Contribution evaluate(RiskContext context) {
        Map<String, DocumentExtraction> documents = context.documents();
        if (documents.isEmpty()) {
            return Contribution.notEvaluable(factorId(),
                    "No se analizó documentación en este expediente — factor no evaluable");
        }

        List<String> findings = new ArrayList<>();
        documents.forEach((type, extraction) -> {
            checkImei(context, type, extraction.fields(), findings);
            checkDocumentDate(context, type, extraction.fields(), findings);
            checkAmount(context, type, extraction.fields(), findings);
        });
        checkDeclaredPoliceReportDate(context, documents, findings);

        if (findings.isEmpty()) {
            return new Contribution(factorId(), 0.0,
                    "Los datos de los documentos coinciden con los del siniestro");
        }
        double score = Math.min(1.0, findings.size() * SCORE_PER_FINDING);
        return new Contribution(factorId(), score, String.join(" · ", findings));
    }

    /**
     * El cruce que motivó el factor: el IMEI que figura en el documento contra el del bien
     * asegurado. Solo corre si la póliza tiene IMEI (ramo Celulares); en Tecnología Portátil no hay
     * contra qué comparar y el chequeo no participa.
     */
    private void checkImei(
            RiskContext context, String type, DocumentExtraction.Fields fields, List<String> findings) {
        String insuredImei = context.policy() == null ? null : context.policy().imei();
        if (insuredImei == null || fields.imei() == null) {
            return;
        }
        if (!insuredImei.equals(fields.imei())) {
            findings.add(String.format(
                    "El IMEI del documento '%s' (%s) no coincide con el del bien asegurado (%s)",
                    type, fields.imei(), insuredImei));
        }
    }

    /**
     * La fecha del documento no puede ser anterior al hecho: una factura de reparación o una
     * constancia policial se emiten después. Se tolera una semana hacia atrás para el caso legítimo
     * de la factura de compra del equipo, que sí es previa.
     */
    private void checkDocumentDate(
            RiskContext context, String type, DocumentExtraction.Fields fields, List<String> findings) {
        if (fields.documentDate() == null || context.claim() == null || context.claim().eventDate() == null) {
            return;
        }
        LocalDate eventDate = context.claim().eventDate().toLocalDate();
        LocalDate earliestAccepted = eventDate.minusDays(DOCUMENT_DATE_TOLERANCE_DAYS);
        if (fields.documentDate().isBefore(earliestAccepted)) {
            findings.add(String.format(
                    "El documento '%s' está fechado el %s, anterior al hecho (%s)",
                    type, fields.documentDate(), eventDate));
        }
    }

    /**
     * D12 · lo que el asegurado <b>declaró</b> sobre su denuncia policial contra lo que dice la
     * constancia. Es la señal que le da sentido a haber guardado las dos fechas por separado: una
     * cosa es haber denunciado tarde (eso lo evalúa la regla del plazo) y otra es declarar una fecha
     * que el papel no respalda.
     *
     * <p>Se compara por día y no por hora: el asegurado declara hora exacta, la constancia
     * habitualmente no, y exigir que coincidan al minuto convertiría en sospechoso a cualquiera que
     * redondeó.
     */
    private void checkDeclaredPoliceReportDate(
            RiskContext context, Map<String, DocumentExtraction> documents, List<String> findings) {
        DocumentExtraction policeReport = documents.get(POLICE_REPORT_TYPE);
        if (policeReport == null || context.claim() == null || context.claim().policeReportAt() == null) {
            return;
        }
        LocalDate onPaper = policeReport.fields().documentDate();
        if (onPaper == null) {
            return;
        }
        LocalDate declared = context.claim().policeReportAt().toLocalDate();
        if (!onPaper.equals(declared)) {
            findings.add(String.format(
                    "La constancia policial está fechada el %s, pero el asegurado declaró haber denunciado el %s",
                    onPaper, declared));
        }
    }

    /** El importe del documento contra el monto reclamado, con tolerancia por redondeos e impuestos. */
    private void checkAmount(
            RiskContext context, String type, DocumentExtraction.Fields fields, List<String> findings) {
        BigDecimal claimed = context.claim() == null ? null : context.claim().claimedAmount();
        if (fields.amount() == null || claimed == null || claimed.signum() == 0) {
            return;
        }
        BigDecimal tolerance = claimed.multiply(AMOUNT_TOLERANCE_RATIO).abs();
        BigDecimal difference = fields.amount().subtract(claimed).abs();
        if (difference.compareTo(tolerance) > 0) {
            findings.add(String.format(
                    "El importe del documento '%s' ($%s) difiere del monto reclamado ($%s)",
                    type, fields.amount(), claimed));
        }
    }
}
