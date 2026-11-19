package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.DocumentAnalysisSummary;
import ar.edu.utn.frba.arbiter.cases.dto.DocumentCorrectionRequest;
import ar.edu.utn.frba.arbiter.cases.exceptions.AnalystProfileNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseAssignedToAnotherAnalystException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotAssignedException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.DocumentCorrectionNotAllowedException;
import ar.edu.utn.frba.arbiter.cases.exceptions.DocumentNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.DocumentAnalysisCorrection;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExtractedField;
import ar.edu.utn.frba.arbiter.cases.models.entities.RequestedDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentAnalysisRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.DocumentAnalysisCorrectionRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.DocumentTypeRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.DocumentType;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class DocumentCorrectionService {

    private static final int REASON_MAX_LENGTH = 255;
    private static final int SHOWN_VALUE_MAX_LENGTH = 60;
    private static final int NAMED_VALUE_MAX_LENGTH = 500;
    private static final int AMOUNT_INTEGER_DIGITS = 12;
    private static final Locale AR = Locale.forLanguageTag("es-AR");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final CaseRepository caseRepository;
    private final CaseDocumentRepository caseDocumentRepository;
    private final CaseDocumentAnalysisRepository documentAnalysisRepository;
    private final DocumentAnalysisCorrectionRepository correctionRepository;
    private final ClaimsAnalystRepository claimsAnalystRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final CaseStatusService caseStatusService;

    @Transactional
    public DocumentAnalysisSummary.Correction correct(Long caseId, Long documentId,
                                                      DocumentCorrectionRequest request) {
        Case caseRecord = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));
        ClaimsAnalyst analyst = assertCallerOwns(caseRecord);
        if (caseRecord.getCurrentStatus().isFinal()) {
            throw DocumentCorrectionNotAllowedException.caseClosed();
        }
        CaseDocument document = caseDocumentRepository.findById(documentId)
                .filter(found -> found.getCaseId().equals(caseId))
                .orElseThrow(() -> new DocumentNotFoundException(caseId, documentId));
        DocumentAnalysisSummary analysis = documentAnalysisRepository.findByCaseId(caseId).stream()
                .filter(read -> documentId.equals(read.documentId()))
                .findFirst()
                .orElseThrow(DocumentCorrectionNotAllowedException::notRead);

        ExtractedField field = request.field();
        String fieldName = field.isNamed() ? correctableName(analysis, field, request.fieldName()) : null;
        String newValue = normalized(field, request.value());
        String previousValue = analysis.currentValue(field, fieldName);
        if (sameValue(field, previousValue, newValue)) {
            throw DocumentCorrectionNotAllowedException.sameValue();
        }

        DocumentAnalysisCorrection saved = correctionRepository.save(DocumentAnalysisCorrection.builder()
                .caseDocumentId(documentId)
                .field(field)
                .fieldName(fieldName)
                .previousValue(previousValue)
                .newValue(newValue)
                .analyst(analyst)
                .build());
        caseStatusService.recordNote(caseRecord, StatusChangeActor.ANALYST,
                reason(analyst, documentName(caseRecord, document), field, fieldName, previousValue, newValue),
                null);
        return new DocumentAnalysisSummary.Correction(field, fieldName, previousValue, newValue,
                fullName(analyst), saved.getCorrectedAt());
    }

    private static String correctableName(DocumentAnalysisSummary analysis, ExtractedField field,
                                          String requested) {
        String name = requested == null ? "" : requested.trim();
        if (name.isEmpty()) {
            throw DocumentCorrectionNotAllowedException.missingFieldName();
        }
        List<DocumentAnalysisSummary.Detail> read = field == ExtractedField.ITEM_ATTRIBUTE
                ? analysis.itemAttributes()
                : analysis.details();
        Stream<String> readNames = read.stream().map(DocumentAnalysisSummary.Detail::name);
        Stream<String> correctedNames = analysis.corrections().stream()
                .filter(correction -> correction.field() == field)
                .map(DocumentAnalysisSummary.Correction::fieldName);
        return Stream.concat(readNames, correctedNames)
                .filter(name::equals)
                .findFirst()
                .orElseThrow(() -> DocumentCorrectionNotAllowedException.unknownField(name));
    }

    private static String normalized(ExtractedField field, String raw) {
        String value = raw.trim();
        return switch (field) {
            case DOCUMENT_DATE -> date(value).toString();
            case AMOUNT -> amount(value).toPlainString();
            case ITEM_DESCRIPTION -> value;
            case ITEM_ATTRIBUTE, DETAIL -> {
                if (value.length() > NAMED_VALUE_MAX_LENGTH) {
                    throw DocumentCorrectionNotAllowedException.tooLong(NAMED_VALUE_MAX_LENGTH);
                }
                yield value;
            }
        };
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw DocumentCorrectionNotAllowedException.invalidDate();
        }
    }

    private static BigDecimal amount(String value) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw DocumentCorrectionNotAllowedException.invalidAmount();
        }
        if (amount.signum() <= 0
                || amount.stripTrailingZeros().scale() > 2
                || amount.precision() - amount.scale() > AMOUNT_INTEGER_DIGITS) {
            throw DocumentCorrectionNotAllowedException.invalidAmount();
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static boolean sameValue(ExtractedField field, String previousValue, String newValue) {
        if (previousValue == null) {
            return false;
        }
        return field == ExtractedField.AMOUNT
                ? new BigDecimal(previousValue).compareTo(new BigDecimal(newValue)) == 0
                : previousValue.equals(newValue);
    }

    private String documentName(Case caseRecord, CaseDocument document) {
        String type = document.getType();
        return documentTypeRepository.findByCode(type)
                .map(DocumentType::getName)
                .or(() -> caseRecord.getRequestedDocuments().stream()
                        .filter(requested -> type.equals(requested.type()))
                        .map(RequestedDocument::description)
                        .filter(Objects::nonNull)
                        .findFirst())
                .orElse(type);
    }

    private static String reason(ClaimsAnalyst analyst, String documentName, ExtractedField field,
                                 String fieldName, String previousValue, String newValue) {
        String text = "%s corrigió %s en «%s»: %s → %s".formatted(fullName(analyst),
                fieldLabel(field, fieldName), documentName, shown(field, previousValue), shown(field, newValue));
        return text.length() <= REASON_MAX_LENGTH ? text : text.substring(0, REASON_MAX_LENGTH);
    }

    private static String fieldLabel(ExtractedField field, String fieldName) {
        return switch (field) {
            case DOCUMENT_DATE -> "la fecha";
            case AMOUNT -> "el importe";
            case ITEM_DESCRIPTION -> "el bien";
            case ITEM_ATTRIBUTE, DETAIL -> "«" + fieldName + "»";
        };
    }

    private static String shown(ExtractedField field, String value) {
        if (value == null) {
            return "sin dato";
        }
        return switch (field) {
            case DOCUMENT_DATE -> LocalDate.parse(value).format(DAY);
            case AMOUNT -> NumberFormat.getCurrencyInstance(AR).format(new BigDecimal(value));
            case ITEM_DESCRIPTION, ITEM_ATTRIBUTE, DETAIL -> value.length() <= SHOWN_VALUE_MAX_LENGTH
                    ? value
                    : value.substring(0, SHOWN_VALUE_MAX_LENGTH - 1) + "…";
        };
    }

    private static String fullName(ClaimsAnalyst analyst) {
        return (analyst.getName() + " " + analyst.getSurname()).trim();
    }

    private ClaimsAnalyst assertCallerOwns(Case caseRecord) {
        String callerEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        ClaimsAnalyst caller = claimsAnalystRepository.findByEmail(callerEmail)
                .orElseThrow(() -> new AnalystProfileNotFoundException(callerEmail));
        if (caseRecord.getAnalyst() == null) {
            throw new CaseNotAssignedException(caseRecord.getId());
        }
        if (!caseRecord.getAnalyst().getId().equals(caller.getId())) {
            throw new CaseAssignedToAnotherAnalystException(caseRecord.getId());
        }
        return caller;
    }
}
