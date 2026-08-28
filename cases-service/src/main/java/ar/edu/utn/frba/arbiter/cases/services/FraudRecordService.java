package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.RegisterFraudRecordRequest;
import ar.edu.utn.frba.arbiter.cases.exceptions.AnalystProfileNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.FraudRecordNotAllowedException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
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
 * <p>Deliberately not part of filing the expert's report, and not part of the decision endpoint
 * either. The expert verifies a fact about one claim; deciding that fact should follow the person
 * into their next claim is a second, separate act — and it's the analyst's, not the expert's and
 * not the system's. Someone has to be accountable for a mark that will change how a person is
 * treated (Ley 25.326), and "el informe lo dijo" is not a name.
 *
 * <p>The record itself lives in classification-service, which owns cross-claim memory about an
 * insured and is where it gets read while scoring. This service writes {@code fraudDetermined} on
 * the case — the column the DER always had and nothing ever set — and hands the record over.
 */
@Service
@RequiredArgsConstructor
public class FraudRecordService {

    private static final Logger log = LoggerFactory.getLogger(FraudRecordService.class);

    /**
     * When the determination can be made. {@code PENDING_ANALYST_REVIEW} is the analyst holding the
     * case with everything in front of them (classification, score, and the expert's report if it
     * was derived); {@code REJECTED} is the same call made at the moment of rejecting.
     *
     * <p>{@code APPROVED} is left out: paying a claim and recording it as fraud contradict each
     * other, and the honest path for a fraud found after payment is reopening the case, not
     * annotating a closed one that says the opposite. {@code PENDING_EXPERT_REPORT} is left out
     * because the evidence being waited on hasn't arrived.
     */
    private static final Set<CaseStatus> ALLOWED_STATUSES =
            Set.of(CaseStatus.PENDING_ANALYST_REVIEW, CaseStatus.REJECTED);

    private final CaseRepository caseRepository;
    private final ExpertAssessmentRepository expertAssessmentRepository;
    private final ClaimsAnalystRepository claimsAnalystRepository;
    private final ClaimsAnalysisClient classificationClient;

    /**
     * The record an expert-confirmed report leaves on its own, with no second click. The expert
     * already proved the fact and the analyst filing the report is transcribing it, so asking them
     * to state it again added a step that gets forgotten — and a forgotten step here means the
     * person walks away unmarked with a report that says otherwise. {@code ANALYST_DECLARED} stays
     * manual: that one IS a judgment call and needs somebody to write down why.
     *
     * <p>No-op if the case already has a record — the analyst may have declared it before the
     * report arrived, and a duplicate would blow up the filing over something already recorded.
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

        // After the record is in: a case flagged as fraud with no record behind it would be a claim
        // nobody can trace back to a determination. Same ordering criterion as the derivation.
        caseRecord.setFraudDetermined(true);
        caseRepository.save(caseRecord);

        log.info("[FraudRecord] Case {} determined fraudulent — source={} analyst={}",
                caseId, request.source(), analyst.getId());
        return registered;
    }

    /**
     * Every fraud record on this case's insured, the case's own included. Filtering it out here
     * would leave the analyst unable to see the record they just created from the very screen they
     * created it on; which one is "this case" is something the caller already knows.
     */
    @Transactional(readOnly = true)
    public List<FraudRecordResponse> insuredRecords(Long caseId) {
        return classificationClient.fraudRecordsOf(findCase(caseId).getInsured().getDni());
    }

    /**
     * "Con respaldo pericial" has to mean there is a report saying so. Checked against the stored
     * verdict and not against what the request claims: the whole difference between the two sources
     * is that one of them can move a score, and it would be worth nothing if picking it were enough.
     */
    private Long resolveExpertBacking(Case caseRecord, FraudRecordSource source) {
        if (source != FraudRecordSource.EXPERT_BACKED) {
            return null;
        }
        Optional<ExpertAssessment> assessment = expertAssessmentRepository.findByCaseId(caseRecord.getId());
        return assessment
                .filter(found -> found.getVerdict() == ExpertVerdict.FRAUD_CONFIRMED)
                .map(ExpertAssessment::getId)
                .orElseThrow(() -> new FraudRecordNotAllowedException(caseRecord.getId()));
    }

    /** Never off the request body — same mechanism as the derivation and the decision endpoint. */
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
