package ar.edu.utn.frba.arbiter.cases.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * An empty provider list alone is ambiguous ("doesn't derive" vs "nobody covers this branch");
 * {@code allowedByRule} and the amounts tell them apart.
 *
 * @param allowedByRule    the insurer's rule lets this case go to this kind of provider; with no
 *                         providers it is still not {@code eligible}
 * @param minClaimedAmount null when the insurer doesn't derive this branch
 */
public record DerivationOptionsResponse(
        boolean eligible,
        boolean allowedByRule,
        BigDecimal minClaimedAmount,
        BigDecimal claimedAmount,
        List<ServiceProviderResponse> providers
) {}
