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
 * Registering a record is deliberately separate from filing the expert's report: the analyst decides
 * whether a verified fact becomes a record that weighs on the person's next claim.
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

    /** Lapsed records included: the window becomes flags, so the analyst reads the same history the engine did. */
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
        // Whether it qualifies, not how much it weighs: that's the scoring config's factor weight.
        boolean scores = record.counts(policy.windowMonths(), today);
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
