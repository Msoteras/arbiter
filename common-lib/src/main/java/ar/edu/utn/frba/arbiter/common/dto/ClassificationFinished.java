package ar.edu.utn.frba.arbiter.common.dto;

import jakarta.validation.constraints.NotNull;

public record ClassificationFinished(@NotNull Outcome outcome) {

    public enum Outcome {
        COMPLETED,
        FAILED
    }
}
