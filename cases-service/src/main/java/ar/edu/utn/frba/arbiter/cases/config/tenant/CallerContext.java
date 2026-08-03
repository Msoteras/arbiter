package ar.edu.utn.frba.arbiter.cases.config.tenant;

import java.util.List;

/**
 * Per-request holder for the claims of the authenticated caller that this module needs beyond
 * the role. Populated by {@link TenantResolvingFilter} from the same parsed token it already
 * reads {@code tenantSchema} from, and cleared when the request ends.
 *
 * <p>Lives here rather than in the {@code SecurityContext} because common-lib's
 * {@code JwtAuthenticationFilter} keeps only the subject and the role — widening it would change
 * a class shared by all 5 modules for something only this one uses, the same trade-off
 * {@link TenantResolvingFilter} already documents.
 *
 * <p><b>Both values are read off a signed token, never off request input.</b> That matters:
 * {@code insurerIds} is what bounds a cross-tenant read to the insurers the caller actually
 * belongs to, so taking it from a query param would let anyone name any tenant.
 */
public final class CallerContext {

    private static final ThreadLocal<Caller> CURRENT = new ThreadLocal<>();

    /**
     * @param insuredId  the caller's DNI when they are an ASEGURADO; null for analysts and
     *                   referents, who have no {@code insured} row
     * @param insurerIds every insurer the caller belongs to. Usually one; more than one is the
     *                   multi-insurer case (an insured with policies at two companies).
     * @param homeTenant the schema the token was issued for. Distinct from
     *                   {@link TenantContext#get()}, which an operation may legitimately move
     *                   (filing a claim runs in the tenant that issued the policy) — comparing
     *                   the two is how a caller knows it switched.
     */
    public record Caller(String insuredId, List<Long> insurerIds, String homeTenant) {

        public Caller {
            insurerIds = insurerIds == null ? List.of() : List.copyOf(insurerIds);
        }

        /** True when the current operation runs in a tenant other than the token's. */
        public boolean movedAwayFromHome() {
            return homeTenant != null && !homeTenant.equals(TenantContext.get());
        }
    }

    private CallerContext() {
    }

    /** Never null: an unauthenticated request reads as a caller with no identity. */
    public static Caller get() {
        Caller caller = CURRENT.get();
        return caller != null ? caller : new Caller(null, List.of(), null);
    }

    public static void set(Caller caller) {
        CURRENT.set(caller);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
