package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.ImageAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageAnalysisRepository extends JpaRepository<ImageAnalysis, Long> {
}
