package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.models.entities.ClassificationLog;
import ar.edu.utn.frba.arbiter.classification.models.entities.RiskAnalysis;
import ar.edu.utn.frba.arbiter.classification.models.repositories.ClassificationLogRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.RiskAnalysisRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Persistence + read mapping of the risk snapshot (now its own risk_analysis row, not
 * columns on ClassificationLog), focused on the "sin scorear" contract: a claim with no
 * scoring config must persist no risk_analysis row and expose null risk — never a real
 * LOW band.
 */
@ExtendWith(MockitoExtension.class)
class ClassificationResultsServiceTest {

    @Mock private ClassificationLogRepository logRepository;
    @Mock private RiskAnalysisRepository riskAnalysisRepository;
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
    void scoredClaim_persistsRiskAnalysis() {
        RiskScore score = new RiskScore(true, 0.72, RiskBand.HIGH,
                List.of(new RiskBreakdownItem("amount_ratio", 0.9, 0.45, 0.405, "monto alto")));

        service.saveResult(7L, response(score), null, 120);

        ArgumentCaptor<RiskAnalysis> captor = ArgumentCaptor.forClass(RiskAnalysis.class);
        verify(riskAnalysisRepository).save(captor.capture());
        RiskAnalysis saved = captor.getValue();
        assertThat(saved.getCaseId()).isEqualTo(7L);
        assertThat(saved.getRiskScore()).isEqualByComparingTo("0.720");
        assertThat(saved.getRiskBand()).isEqualTo(RiskBand.HIGH);
        assertThat(saved.getRiskBreakdown()).isEqualTo(score.breakdown());
    }

    @Test
    void noConfigClaim_doesNotPersistRiskAnalysis() {
        service.saveResult(7L, response(RiskScore.notScored()), null, 120);

        verify(riskAnalysisRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getStatus_noRisk_exposedAsSinScorearNotLow() {
        ClassificationLog log = new ClassificationLog();
        log.setClassification(Classification.LLM_RECOMIENDA_APROBAR);
        log.setSource("LLM");
        when(logRepository.findFirstByCaseIdOrderByIdDesc(7L)).thenReturn(Optional.of(log));
        when(riskAnalysisRepository.findFirstByCaseIdOrderByIdDesc(7L)).thenReturn(Optional.empty());

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
        when(logRepository.findFirstByCaseIdOrderByIdDesc(7L)).thenReturn(Optional.of(log));

        RiskAnalysis analysis = new RiskAnalysis();
        analysis.setCaseId(7L);
        analysis.setRiskScore(new BigDecimal("0.720"));
        analysis.setRiskBand(RiskBand.HIGH);
        analysis.setRiskBreakdown(List.of(new RiskBreakdownItem("amount_ratio", 0.9, 0.45, 0.405, "monto alto")));
        when(riskAnalysisRepository.findFirstByCaseIdOrderByIdDesc(7L)).thenReturn(Optional.of(analysis));

        ClaimResponse exposed = service.getStatus(7L);

        assertThat(exposed.riskScore()).isCloseTo(0.72, within(1e-9));
        assertThat(exposed.riskBand()).isEqualTo(RiskBand.HIGH);
        assertThat(exposed.riskBreakdown()).hasSize(1);
    }
}
