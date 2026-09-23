package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.RegisterFraudRecordRequest;
import ar.edu.utn.frba.arbiter.cases.exceptions.AnalystProfileNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.FraudRecordNotAllowedException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ExpertAssessmentRepository;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordRequest;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordResponse;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.ExpertVerdict;
import ar.edu.utn.frba.arbiter.common.enums.FraudRecordSource;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The analyst's determination that a case ended in fraud, and the record it leaves on the insured.
 *
 * <p>A separate act from the expert report and the decision: a mark that follows a person into
 * their next claim needs an accountable analyst (Ley 25.326). The record itself lives in
 * classification-service, which reads it while scoring; this service only sets
 * {@code fraudDetermined} on the case.
 */
@Service
@RequiredArgsConstructor
public class FraudRecordService {

    private static final Logger log = LoggerFactory.getLogger(FraudRecordService.class);

    /**
     * {@code APPROVED} is left out because paying a claim and recording it as fraud contradict each
     * other; {@code PENDING_EXPERT_REPORT} because the evidence hasn't arrived yet.
     */
    private static final Set<CaseStatus> ALLOWED_STATUSES =
            Set.of(CaseStatus.PENDING_ANALYST_REVIEW, CaseStatus.REJECTED);

    private final CaseRepository caseRepository;
    private final ExpertAssessmentRepository expertAssessmentRepository;
    private final ClaimsAnalystRepository claimsAnalystRepository;
    private final ClaimsAnalysisClient classificationClient;

    /**
     * Recorded automatically when an expert report confirms fraud, since the analyst filing it is
     * only transcribing the expert's finding; {@code ANALYST_DECLARED} stays manual. No-op if the
     * case already has a record, which would otherwise fail the report filing.
     */
    @Transactional
    public Optional<FraudRecordResponse> registerFromExpertReport(Long caseId, String reason) {
        boolean alreadyRecorded = insuredRecords(caseId).stream()
                .anyMatch(record -> caseId.equals(record.caseId()));
        if (alreadyRecorded) {
            log.info("[FraudRecord] Case {} already had a record — expert report adds none", caseId);
            return Optional.empty();
        }
        return Optional.of(register(caseId,
                new RegisterFraudRecordRequest(FraudRecordSource.EXPERT_BACKED, reason)));
    }

    @Transactional
    public FraudRecordResponse register(Long caseId, RegisterFraudRecordRequest request) {
        Case caseRecord = findCase(caseId);
        if (!ALLOWED_STATUSES.contains(caseRecord.getStatus())) {
            throw new FraudRecordNotAllowedException(caseId, caseRecord.getStatus());
        }

        Long expertAssessmentId = resolveExpertBacking(caseRecord, request.source());
        ClaimsAnalyst analyst = callerAnalyst();

        FraudRecordResponse registered = classificationClient.registerFraudRecord(new FraudRecordRequest(
                caseRecord.getInsured().getDni(),
                caseId,
                request.source(),
                request.reason(),
                expertAssessmentId,
                analyst.getId(),
                analyst.getName() + " " + analyst.getSurname()));

        // Only after the record exists, so a flagged case always has a traceable determination.
        caseRecord.setFraudDetermined(true);
        caseRepository.save(caseRecord);

        log.info("[FraudRecord] Case {} determined fraudulent — source={} analyst={}",
                caseId, request.source(), analyst.getId());
        return registered;
    }

    /** The case's own record included, so the analyst sees the one they just created. */
    @Transactional(readOnly = true)
    public List<FraudRecordResponse> insuredRecords(Long caseId) {
        return classificationClient.fraudRecordsOf(findCase(caseId).getInsured().getDni());
    }

    /**
     * Checked against the stored verdict, not the request: an expert-backed record can move a
     * score, so choosing that source must require an actual report.
     */
    private Long resolveExpertBacking(Case caseRecord, FraudRecordSource source) {
        if (source != FraudRecordSource.EXPERT_BACKED) {
            return null;
        }
        // Only an assessment can back a record; a repair investigates nothing.
        Optional<ExpertAssessment> assessment = expertAssessmentRepository
                .findByCaseIdAndProviderType(caseRecord.getId(), ProviderType.ESTUDIO_LIQUIDADOR);
        return assessment
                .filter(found -> found.getVerdict() == ExpertVerdict.FRAUD_CONFIRMED)
                .map(ExpertAssessment::getId)
                .orElseThrow(() -> new FraudRecordNotAllowedException(caseRecord.getId()));
    }

    /** From the JWT, never from the request body. */
    private ClaimsAnalyst callerAnalyst() {
        String callerEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        return claimsAnalystRepository.findByEmail(callerEmail)
                .orElseThrow(() -> new AnalystProfileNotFoundException(callerEmail));
    }

    private Case findCase(Long caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));
    }
}
