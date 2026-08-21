package ar.edu.utn.frba.arbiter.cases.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Everything the analyst's screen needs to offer (or not offer) a derivation, in one call.
 *
 * <p>The firm list alone would be ambiguous: empty could mean "this insurer doesn't derive" or
 * "nobody covers this branch", and those need different copy. {@code eligible} plus the two
 * amounts let the screen say which one it is.
 *
 * @param eligible         whether the analyst may derive this case right now.
 * @param minClaimedAmount the insurer's threshold, or null when it doesn't derive this branch.
 * @param claimedAmount    what this case claims, so the screen can show the comparison rather
 *                         than just the verdict.
 */
public record DerivationOptionsResponse(
        boolean eligible,
        BigDecimal minClaimedAmount,
        BigDecimal claimedAmount,
        List<ExpertFirmResponse> firms
) {}
