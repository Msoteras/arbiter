package ar.edu.utn.frba.arbiter.cases.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * An empty firm list alone is ambiguous ("doesn't derive" vs "nobody covers this branch");
 * {@code eligible} and the amounts tell them apart.
 *
 * @param minClaimedAmount null when the insurer doesn't derive this branch
 */
public record DerivationOptionsResponse(
        boolean eligible,
        BigDecimal minClaimedAmount,
        BigDecimal claimedAmount,
        List<ExpertFirmResponse> firms
) {}
