package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.classification.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.classification.exceptions.InvalidClassificationException;
import ar.edu.utn.frba.arbiter.classification.models.entities.ClassificationLog;
import ar.edu.utn.frba.arbiter.classification.models.repositories.ClassificationLogRepository;
import ar.edu.utn.frba.arbiter.common.dto.RiskBreakdownItem;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalystDecisionTest {

    @Mock
    private ClassificationLogRepository logRepository;

    @Mock
    private OllamaProperties ollamaProperties;

    @InjectMocks
    private ClassificationResultsService resultsService;

    @Test
    void recordAnalystDecision_createsNewImmutableRow() {
        Long caseId = 42L;
        ClassificationLog original = classificationLog(caseId, Classification.LLM_RECOMIENDA_APROBAR);
        when(logRepository.findFirstByCaseIdOrderByIdDesc(caseId)).thenReturn(Optional.of(original));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest("analyst-1", "APROBAR"));

        ArgumentCaptor<ClassificationLog> captor = ArgumentCaptor.forClass(ClassificationLog.class);
        verify(logRepository).save(captor.capture());
        ClassificationLog saved = captor.getValue();

        assertThat(saved).isNotSameAs(original);
        assertThat(saved.getId()).isNull();
        assertThat(saved.getCaseId()).isEqualTo(caseId);
        assertThat(saved.getSource()).isEqualTo("ANALYST");
        assertThat(saved.getClassification()).isEqualTo(Classification.LLM_RECOMIENDA_APROBAR);
        assertThat(saved.getAnalystId()).isEqualTo("analyst-1");
        assertThat(saved.getDecision()).isEqualTo("APPROVE");
        assertThat(saved.getDecisionTimestamp()).isNotNull();
    }

    @Test
    void recordAnalystDecision_carriesRiskSnapshotAndInsuredName() {
        Long caseId = 42L;
        ClassificationLog original = classificationLog(caseId, Classification.LLM_NO_RECOMIENDA_APROBAR);
        original.setRiskScore(BigDecimal.valueOf(0.72));
        original.setRiskBand(RiskBand.HIGH);
        original.setRiskBreakdown(List.of(new RiskBreakdownItem("amount_ratio", 0.9, 0.45, 0.405, "monto alto")));
        original.setInsuredName("Juan Pérez");
        when(logRepository.findFirstByCaseIdOrderByIdDesc(caseId)).thenReturn(Optional.of(original));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest("analyst-1", "APROBAR"));

        ArgumentCaptor<ClassificationLog> captor = ArgumentCaptor.forClass(ClassificationLog.class);
        verify(logRepository).save(captor.capture());
        ClassificationLog saved = captor.getValue();

        // The decision row (now the latest for the case) must preserve the fraud snapshot,
        // otherwise getStatus would report a null risk once the analyst decides.
        assertThat(saved.getRiskScore()).isEqualByComparingTo("0.72");
        assertThat(saved.getRiskBand()).isEqualTo(RiskBand.HIGH);
        assertThat(saved.getRiskBreakdown()).isEqualTo(original.getRiskBreakdown());
        assertThat(saved.getInsuredName()).isEqualTo("Juan Pérez");
    }

    @Test
    void recordAnalystDecision_rejectNormalization() {
        Long caseId = 7L;
        ClassificationLog original = classificationLog(caseId, Classification.LLM_NO_RECOMIENDA_APROBAR);
        when(logRepository.findFirstByCaseIdOrderByIdDesc(caseId)).thenReturn(Optional.of(original));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest("analyst-2", "RECHAZAR"));

        ArgumentCaptor<ClassificationLog> captor = ArgumentCaptor.forClass(ClassificationLog.class);
        verify(logRepository).save(captor.capture());
        assertThat(captor.getValue().getDecision()).isEqualTo("REJECT");
    }

    @Test
    void recordAnalystDecision_doesNotMutateOriginalRow() {
        Long caseId = 42L;
        ClassificationLog original = classificationLog(caseId, Classification.LLM_RECOMIENDA_APROBAR);
        when(logRepository.findFirstByCaseIdOrderByIdDesc(caseId)).thenReturn(Optional.of(original));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest("analyst-1", "APPROVE"));

        assertThat(original.getAnalystId()).isNull();
        assertThat(original.getDecision()).isNull();
        assertThat(original.getDecisionTimestamp()).isNull();
    }

    @Test
    void recordAnalystDecision_throwsWhenNoClassificationExists() {
        Long caseId = 99L;
        when(logRepository.findFirstByCaseIdOrderByIdDesc(caseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest("analyst-1", "APPROVE")))
                .isInstanceOf(InvalidClassificationException.class);
    }

    private ClassificationLog classificationLog(Long caseId, Classification classification) {
        ClassificationLog log = new ClassificationLog();
        log.setId(100L);
        log.setCaseId(caseId);
        log.setSource("LLM");
        log.setClassification(classification);
        log.setConfidence(BigDecimal.valueOf(0.85));
        log.setFactors(List.of("factor-1", "factor-2"));
        return log;
    }
}
