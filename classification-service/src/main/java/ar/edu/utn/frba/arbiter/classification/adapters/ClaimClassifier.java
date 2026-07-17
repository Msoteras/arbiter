package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.dto.ClassificationRequest;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;

public interface ClaimClassifier {

    ClassificationResponse classify(ClassificationRequest request);
}
