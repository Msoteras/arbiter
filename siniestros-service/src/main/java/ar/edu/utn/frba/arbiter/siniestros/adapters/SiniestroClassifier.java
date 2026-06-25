package ar.edu.utn.frba.arbiter.siniestros.adapters;

import ar.edu.utn.frba.arbiter.siniestros.dto.ClassificationRequest;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClassificationResponse;

public interface SiniestroClassifier {

    ClassificationResponse classify(ClassificationRequest request);
}
