package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.classification.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.classification.exceptions.InvalidClassificationException;
import ar.edu.utn.frba.arbiter.classification.models.entities.ClassificationLog;
import ar.edu.utn.frba.arbiter.classification.models.repositories.ClassificationLogRepository;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    void recordAnalystDecision_persistsDecisionAndTimestamp() {
        Long caseId = 42L;
        ClassificationLog log = new ClassificationLog();
        log.setCaseId(caseId);
        log.setClassification(Classification.LLM_RECOMIENDA_APROBAR);
        when(logRepository.findFirstByCaseIdOrderByIdDesc(caseId)).thenReturn(Optional.of(log));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest("analyst-1", "APROBAR"));

        assertThat(log.getAnalystId()).isEqualTo("analyst-1");
        assertThat(log.getDecision()).isEqualTo("APPROVE");
        assertThat(log.getDecisionTimestamp()).isNotNull();
        verify(logRepository).save(log);
    }

    @Test
    void recordAnalystDecision_rejectNormalization() {
        Long caseId = 7L;
        ClassificationLog log = new ClassificationLog();
        log.setCaseId(caseId);
        log.setClassification(Classification.LLM_NO_RECOMIENDA_APROBAR);
        when(logRepository.findFirstByCaseIdOrderByIdDesc(caseId)).thenReturn(Optional.of(log));

        resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest("analyst-2", "RECHAZAR"));

        assertThat(log.getDecision()).isEqualTo("REJECT");
    }

    @Test
    void recordAnalystDecision_throwsWhenNoClassificationExists() {
        Long caseId = 99L;
        when(logRepository.findFirstByCaseIdOrderByIdDesc(caseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                resultsService.recordAnalystDecision(caseId, new AnalystDecisionRequest("analyst-1", "APPROVE")))
                .isInstanceOf(InvalidClassificationException.class);
    }
}
