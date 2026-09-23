package ar.edu.utn.frba.arbiter.auth.dto;

import java.util.List;

/**
 * What one bulk provisioning run did.
 *
 * @param usersReused    existing accounts matched by email: someone insured at two companies is one identity
 * @param insurersLinked {@code user_insurer} rows added; linking is what gives an existing user this
 *                       insurer's policies
 * @param skipped        one line per policyholder that couldn't be provisioned, with the reason
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
