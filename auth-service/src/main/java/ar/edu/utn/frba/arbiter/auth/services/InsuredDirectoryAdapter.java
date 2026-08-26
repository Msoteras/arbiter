package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.dto.InsuredDirectoryEntry;

import java.util.List;

/**
 * Reads the insurer's own database to find out who its policyholders are (Adapter pattern, same
 * treatment Auth0 and SendGrid get).
 *
 * <p>Exists because nothing else could answer the question. The {@code InsurerAdapter} the portal
 * uses looks a policy up by number, or a person's policies up by document — both need to already
 * know who they are asking about. Provisioning accounts in bulk is the one flow that has to
 * <b>enumerate</b> them, so it needs its own read.
 */
public interface InsuredDirectoryAdapter {

    /**
     * Policyholders with at least one policy in force right now, deduplicated by document.
     *
     * <p>In force is the filter on purpose: an expired policy means nothing left to report, so
     * inviting that person is a mail they have no use for. Same predicate the portal already uses
     * for "my policies" ({@code vigencia_hasta >= NOW()}, on the full timestamp and not the date,
     * so a policy that lapsed at 08:00 is not still in force at 14:00).
     *
     * @param insurerDbSchema the company's schema ({@code aseguradora_bbva})
     */
    List<InsuredDirectoryEntry> findWithPoliciesInForce(String insurerDbSchema);
}
