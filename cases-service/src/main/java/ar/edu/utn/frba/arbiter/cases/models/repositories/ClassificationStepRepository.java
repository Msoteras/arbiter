package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.ClassificationStepRecord;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Repository
public interface ClassificationStepRepository extends JpaRepository<ClassificationStepRecord, Long> {

    /** Ties on {@code id}: two steps can share a millisecond. */
    List<ClassificationStepRecord> findByCaseIdInOrderByOccurredAtAscIdAsc(Collection<Long> caseIds);

    List<ClassificationStepRecord> findByStageInAndOccurredAtGreaterThanEqual(
            Collection<ClassificationStage> stages, Instant since);
}
