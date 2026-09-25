package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.LlmAnalysis;
import ar.edu.utn.frba.arbiter.classification.models.entities.LlmReason;
import ar.edu.utn.frba.arbiter.classification.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class LlmAnalysisRepositoryTests extends AbstractPersistenceIT {

    private static final AtomicLong NEXT_CASE_ID = new AtomicLong(900_000);

    @Autowired
    private LlmAnalysisRepository repository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void enableStatistics() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
    }

    @AfterEach
    void disableStatistics() {
        statistics.setStatisticsEnabled(false);
    }

    @Test
    void latestRunComesBackAloneWithAllItsReasonsInOneQuery(CapturedOutput output) {
        long caseId = NEXT_CASE_ID.incrementAndGet();
        save(caseId, Classification.LLM_SOLICITA_REVISION_MANUAL, "first run");
        save(caseId, Classification.FALTA_DOCUMENTACION, "second run", "missing invoice");
        LlmAnalysis latest = save(caseId, Classification.LLM_NO_RECOMIENDA_APROBAR,
                "recidivist", "vague account", "high amount");
        statistics.clear();

        Optional<LlmAnalysis> found = repository.findLatestByCaseId(caseId);

        assertThat(found).get().extracting(LlmAnalysis::getId).isEqualTo(latest.getId());
        assertThat(found.get().getReasons()).extracting(LlmReason::getReason)
                .containsExactly("recidivist", "vague account", "high amount");
        assertThat(statistics.getEntityStatistics(LlmAnalysis.class.getName()).getLoadCount())
                .as("analyses materialized").isEqualTo(1);
        assertThat(statistics.getPrepareStatementCount()).as("round trips").isEqualTo(1);
        assertThat(output).doesNotContain("HHH90003004");
    }

    @Test
    void latestRunWithoutReasonsIsStillFound() {
        long caseId = NEXT_CASE_ID.incrementAndGet();
        save(caseId, Classification.LLM_RECOMIENDA_APROBAR, "older reason");
        LlmAnalysis latest = save(caseId, Classification.FALTA_DOCUMENTACION);

        Optional<LlmAnalysis> found = repository.findLatestByCaseId(caseId);

        assertThat(found).get().extracting(LlmAnalysis::getId).isEqualTo(latest.getId());
        assertThat(found.get().getReasons()).isEmpty();
    }

    @Test
    void anotherCasesNewerRunDoesNotWin() {
        long caseId = NEXT_CASE_ID.incrementAndGet();
        LlmAnalysis own = save(caseId, Classification.LLM_RECOMIENDA_APROBAR, "own reason");
        save(NEXT_CASE_ID.incrementAndGet(), Classification.LLM_NO_RECOMIENDA_APROBAR, "foreign reason");

        Optional<LlmAnalysis> found = repository.findLatestByCaseId(caseId);

        assertThat(found).get().extracting(LlmAnalysis::getId).isEqualTo(own.getId());
        assertThat(found.get().getReasons()).extracting(LlmReason::getReason).containsExactly("own reason");
    }

    @Test
    void caseNeverClassifiedResolvesEmpty() {
        assertThat(repository.findLatestByCaseId(NEXT_CASE_ID.incrementAndGet())).isEmpty();
    }

    private LlmAnalysis save(long caseId, Classification recommendation, String... reasons) {
        LlmAnalysis analysis = new LlmAnalysis();
        analysis.setCaseId(caseId);
        analysis.setRecommendation(recommendation);
        analysis.setModel("test-model");
        analysis.setPromptVersion("test");
        analysis.setAnalyzedAt(Instant.now());
        List.of(reasons).forEach(analysis::addReason);
        return repository.saveAndFlush(analysis);
    }
}
