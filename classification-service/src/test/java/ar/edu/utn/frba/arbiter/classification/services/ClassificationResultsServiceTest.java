package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.models.entities.ClassificationLog;
import ar.edu.utn.frba.arbiter.classification.models.repositories.ClassificationLogRepository;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScore;
import ar.edu.utn.frba.arbiter.common.dto.ClaimResponse;
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
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Persistence + read mapping of the risk snapshot, focused on the "sin scorear" contract: a claim
 * with no scoring config must persist and expose null risk — never a real LOW band.
 */
@ExtendWith(MockitoExtension.class)
class ClassificationResultsServiceTest {

    @Mock private ClassificationLogRepository logRepository;
    @Mock private OllamaProperties ollamaProperties;

    @InjectMocks private ClassificationResultsService service;

    private static ClassificationResponse response(RiskScore riskScore) {
        return ClassificationResponse.builder()
                .classification(Classification.FAST_TRACK)
                .factors(List.of("ok"))
                .confidence(1.0)
                .deterministicFastTrack(true)   // avoids needing the Ollama model/prompt fields
                .riskScore(riskScore)
                .build();
    }

    @Test
    void scoredClaim_persistsRiskSnapshot() {
        RiskScore score = new RiskScore(true, 0.72, RiskBand.HIGH,
                List.of(new RiskBreakdownItem("amount_ratio", 0.9, 0.45, 0.405, "monto alto")));

        service.saveResult(7L, response(score), null, 120);

        ArgumentCaptor<ClassificationLog> captor = ArgumentCaptor.forClass(ClassificationLog.class);
        verify(logRepository).save(captor.capture());
        ClassificationLog saved = captor.getValue();
        assertThat(saved.getRiskScore()).isEqualByComparingTo("0.720");
        assertThat(saved.getRiskBand()).isEqualTo(RiskBand.HIGH);
        assertThat(saved.getRiskBreakdown()).isEqualTo(score.breakdown());
    }

    @Test
    void noConfigClaim_persistsNullRisk_notLow() {
        service.saveResult(7L, response(RiskScore.notScored()), null, 120);

        ArgumentCaptor<ClassificationLog> captor = ArgumentCaptor.forClass(ClassificationLog.class);
        verify(logRepository).save(captor.capture());
        ClassificationLog saved = captor.getValue();
        assertThat(saved.getRiskScore()).isNull();
        assertThat(saved.getRiskBand()).isNull();
        assertThat(saved.getRiskBreakdown()).isNull();
    }

    @Test
    void getStatus_noRisk_exposedAsSinScorearNotLow() {
        ClassificationLog log = new ClassificationLog();
        log.setClassification(Classification.LLM_RECOMIENDA_APROBAR);
        log.setSource("LLM");
        // risk fields left null
        when(logRepository.findFirstByCaseIdOrderByIdDesc(7L)).thenReturn(Optional.of(log));

        ClaimResponse exposed = service.getStatus(7L);

        assertThat(exposed.riskScore()).isNull();
        assertThat(exposed.riskBand()).isNull();
        assertThat(exposed.riskBreakdown()).isNull();
    }

    @Test
    void getStatus_scored_exposesBandAndScore() {
        ClassificationLog log = new ClassificationLog();
        log.setClassification(Classification.LLM_NO_RECOMIENDA_APROBAR);
        log.setSource("LLM");
        log.setRiskScore(new BigDecimal("0.720"));
        log.setRiskBand(RiskBand.HIGH);
        log.setRiskBreakdown(List.of(new RiskBreakdownItem("amount_ratio", 0.9, 0.45, 0.405, "monto alto")));
        when(logRepository.findFirstByCaseIdOrderByIdDesc(7L)).thenReturn(Optional.of(log));

        ClaimResponse exposed = service.getStatus(7L);

        assertThat(exposed.riskScore()).isCloseTo(0.72, within(1e-9));
        assertThat(exposed.riskBand()).isEqualTo(RiskBand.HIGH);
        assertThat(exposed.riskBreakdown()).hasSize(1);
    }
}
