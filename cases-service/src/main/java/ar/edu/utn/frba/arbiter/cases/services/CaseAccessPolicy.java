package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Case ownership, which {@code @PreAuthorize} doesn't check: an ASEGURADO sees only their own cases.
 * Analysts and referents are bounded by their tenant schema.
 */
@Component
public class CaseAccessPolicy {

    private static final String INSURED_ROLE = "ROLE_ASEGURADO";
    private static final String REFERENT_ROLE = "ROLE_REFERENTE_ASEGURADORA";
    private static final String ANALYST_ROLE = "ROLE_ANALISTA_SINIESTROS";

    /**
     * 404 rather than 403 on purpose: case ids are sequential, so a 403 would let anyone map
     * which cases exist by probing.
     */
    public void assertCanRead(Case caseRecord) {
        if (!canRead(caseRecord)) {
            throw new CaseNotFoundException(caseRecord.getId());
        }
    }

    public boolean canRead(Case caseRecord) {
        if (!currentUserIsInsured()) {
            return true;
        }
        String callerDni = CallerContext.get().insuredId();
        return callerDni != null && callerDni.equals(caseRecord.getInsured().getDni());
    }

    public boolean currentUserIsInsured() {
        return hasAuthority(INSURED_ROLE);
    }

    /** Analysts and referents share the assignment endpoint; the audit trail tells them apart. */
    public StatusChangeActor currentAssignmentActor() {
        return hasAuthority(REFERENT_ROLE) ? StatusChangeActor.REFERENT : StatusChangeActor.ANALYST;
    }

    /**
     * Which side of a case's conversation the caller is on. {@code REFERENT} reads the thread but
     * doesn't write to it. Null for any other role.
     */
    public StatusChangeActor currentParty() {
        if (hasAuthority(INSURED_ROLE)) {
            return StatusChangeActor.INSURED;
        }
        if (hasAuthority(ANALYST_ROLE)) {
            return StatusChangeActor.ANALYST;
        }
        return hasAuthority(REFERENT_ROLE) ? StatusChangeActor.REFERENT : null;
    }

    private boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }
}
