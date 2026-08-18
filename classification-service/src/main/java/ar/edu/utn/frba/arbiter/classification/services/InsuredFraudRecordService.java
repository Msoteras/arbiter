package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.RulesAdapter;
import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.exceptions.FraudRecordAlreadyExistsException;
import ar.edu.utn.frba.arbiter.classification.exceptions.UnsupportedFraudRecordException;
import ar.edu.utn.frba.arbiter.classification.models.entities.InsuredFraudRecord;
import ar.edu.utn.frba.arbiter.classification.models.repositories.InsuredFraudRecordRepository;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordRequest;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordResponse;
import ar.edu.utn.frba.arbiter.common.enums.FraudRecordSource;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Registration and read-back of fraud records against an insured.
 *
 * <p>Registering is deliberately a separate act from filing the expert's report. The expert
 * verifies a fact about one claim; the analyst decides that fact constitutes a record that will
 * weigh on the person's next one. Collapsing the two would mean an external party's report
 * silently starting a file on someone.
 */
@Service
@RequiredArgsConstructor
public class InsuredFraudRecordService {

    private static final Logger log = LoggerFactory.getLogger(InsuredFraudRecordService.class);

    private final InsuredFraudRecordRepository repository;
    private final RulesAdapter rulesAdapter;

    @Transactional
    public FraudRecordResponse register(FraudRecordRequest request) {
        repository.findByCaseId(request.caseId()).ifPresent(existing -> {
            throw new FraudRecordAlreadyExistsException(request.caseId());
        });
        if (request.source() == FraudRecordSource.EXPERT_BACKED && request.expertAssessmentId() == null) {
            throw new UnsupportedFraudRecordException(request.caseId());
        }

        InsuredFraudRecord saved = repository.save(InsuredFraudRecord.builder()
                .insuredDni(request.insuredDni())
                .caseId(request.caseId())
                .source(request.source())
                .reason(request.reason())
                .expertAssessmentId(request.expertAssessmentId())
                .declaredByAnalystId(request.declaredByAnalystId())
                .declaredByAnalystName(request.declaredByAnalystName())
                .build());

        log.info("[FraudRecord] Registered — case={} source={} analyst={}",
                saved.getCaseId(), saved.getSource(), saved.getDeclaredByAnalystId());
        return toResponse(saved, rulesAdapter.getFraudRecordPolicy(), LocalDate.now());
    }

    /**
     * Every record about the insured, lapsed ones included. The window is applied as
     * {@code inForce}/{@code scores} flags instead of as a filter: an analyst who can see "hubo un
     * antecedente en 2019, fuera de ventana" is reading the same history the engine read, which is
     * the point of the record being auditable at all.
     */
    @Transactional(readOnly = true)
    public List<FraudRecordResponse> findByInsured(String insuredDni) {
        BusinessRules.FraudRecordPolicy policy = rulesAdapter.getFraudRecordPolicy();
        LocalDate today = LocalDate.now();
        return repository.findByInsuredDniOrderByDeclaredAtDesc(insuredDni).stream()
                .map(record -> toResponse(record, policy, today))
                .toList();
    }

    private FraudRecordResponse toResponse(
            InsuredFraudRecord record, BusinessRules.FraudRecordPolicy policy, LocalDate today) {
        boolean inForce = record.inForce(policy.windowMonths(), today);
        // An insurer with the rule off still sees its records; what it doesn't get is any of them
        // counting. Same reading the engine does, so screen and score can't tell different stories.
        boolean scores = policy.enabled() && record.counts(policy.windowMonths(), today);
        return new FraudRecordResponse(
                record.getId(),
                record.getInsuredDni(),
                record.getCaseId(),
                record.getSource(),
                record.getReason(),
                record.getExpertAssessmentId(),
                record.getDeclaredByAnalystName(),
                record.getDeclaredAt(),
                inForce,
                scores);
    }
}
