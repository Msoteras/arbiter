package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.ScoreBand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScoreBandRepository extends JpaRepository<ScoreBand, Long> {

    List<ScoreBand> findByScoringConfiguration_Id(Long scoringConfigurationId);

    void deleteByScoringConfiguration_Id(Long scoringConfigurationId);
}
