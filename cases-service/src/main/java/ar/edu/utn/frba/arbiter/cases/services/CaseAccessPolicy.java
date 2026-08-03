package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Who is allowed to read a given case.
 *
 * <p>{@code @PreAuthorize} on the controller only answers "which roles may call this endpoint",
 * which for {@code GET /cases/{id}} was gating "logged in" and not "yours" — any authenticated
 * user could read any case in the tenant. This closes that: an ASEGURADO sees only their own.
 *
 * <p>Analysts and referents are deliberately unrestricted <b>within their tenant</b>: reviewing
 * other people's claims is their job, and the schema already bounds them to one insurer.
 */
@Component
public class CaseAccessPolicy {

    private static final String INSURED_ROLE = "ROLE_ASEGURADO";

    /**
     * Fails as {@link CaseNotFoundException} (404) rather than a 403 on purpose: telling a
     * stranger "this case exists but isn't yours" leaks that it exists, and case ids are
     * sequential, so a 403 would let anyone map the whole table by probing.
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

    /** True when the caller's role is ASEGURADO — the only role scoped to its own cases. */
    public boolean currentUserIsInsured() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(INSURED_ROLE::equals);
    }
}
