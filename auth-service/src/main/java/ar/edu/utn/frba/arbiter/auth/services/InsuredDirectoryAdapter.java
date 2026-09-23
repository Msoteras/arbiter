package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.dto.InsuredDirectoryEntry;

import java.util.List;

/** Enumerates the insurer's policyholders from its own database, for bulk account provisioning. */
public interface InsuredDirectoryAdapter {

    /**
     * Policyholders with at least one policy in force, deduplicated by document. Compares the full
     * timestamp, not the date, same as the portal's "my policies".
     */
    List<InsuredDirectoryEntry> findWithPoliciesInForce(String insurerDbSchema);
}
