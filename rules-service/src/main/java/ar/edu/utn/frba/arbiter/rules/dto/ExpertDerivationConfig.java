package ar.edu.utn.frba.arbiter.rules.dto;

import java.math.BigDecimal;

/**
 * Shape of the {@code configuration} JSONB on an {@code EXPERT_DERIVATION} rule.
 *
 * @param minClaimedAmount the claimed amount from which deriving to an expert is worth it. A
 *                         peritaje costs a fixed fee, so below some amount it costs more than the
 *                         claim — which is why an insurer selling only phone coverage may never
 *                         derive while a multiline one does it routinely. The number is the
 *                         insurer's, not the platform's.
 */
public record ExpertDerivationConfig(BigDecimal minClaimedAmount) {}
