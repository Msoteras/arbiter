package ar.edu.utn.frba.arbiter.auth.dto;

import java.util.List;

/**
 * What one bulk provisioning run did. Reported per run so the referente can tell an idle re-run
 * ("everyone already had an account") apart from one that actually did something.
 *
 * @param scanned      policyholders with a policy in force the insurer's database returned
 * @param usersCreated accounts that did not exist on the platform and were created
 * @param usersReused  people who already had an account — matched by email, and left alone.
 *                     Someone insured at two companies is one identity, never a second row
 * @param insurersLinked memberships added ({@code user_insurer}). This is what actually gives an
 *                     existing user this insurer's policies: the portal reads them live off the
 *                     signed {@code insurerIds} claim, so linking is what "appends" them
 * @param profilesCreated tenant {@code insured} rows created for people who had none here
 * @param invited      invitation mails sent
 * @param skipped      one line per policyholder that could not be provisioned, with the reason
 */
public record ProvisioningSummary(
        int scanned,
        int usersCreated,
        int usersReused,
        int insurersLinked,
        int profilesCreated,
        int invited,
        List<String> skipped
) {
}
