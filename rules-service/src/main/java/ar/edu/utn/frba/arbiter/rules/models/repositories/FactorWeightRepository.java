package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.FactorWeight;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FactorWeightRepository extends JpaRepository<FactorWeight, Long> {

    List<FactorWeight> findByScoringConfiguration_Id(Long scoringConfigurationId);

    void deleteByScoringConfiguration_Id(Long scoringConfigurationId);
}
