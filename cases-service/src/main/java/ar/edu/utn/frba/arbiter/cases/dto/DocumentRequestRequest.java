package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** What the analyst asks the insured for. Every item has to arrive before the case is reclassified. */
public record DocumentRequestRequest(
        @NotEmpty(message = "documents is required") List<@Valid Item> documents
) {

    /** The type an item carries for a document the catalog doesn't name. */
    public static final String OTHER = "other";

    /**
     * @param type        a code of the branch's catalog, or {@link #OTHER}
     * @param description only for {@link #OTHER}: what the insured is asked for
     * @param reason      only for {@link #OTHER}: why, for the model; the insured never sees it
     */
    public record Item(
            @NotBlank(message = "type is required") String type,
            @Size(max = 255, message = "description must be at most 255 characters") String description,
            @Size(max = 1000, message = "reason must be at most 1000 characters") String reason
    ) {
    }
}
