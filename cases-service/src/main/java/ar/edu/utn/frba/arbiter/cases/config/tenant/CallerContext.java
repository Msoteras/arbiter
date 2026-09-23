package ar.edu.utn.frba.arbiter.cases.config.tenant;

import java.util.List;

/**
 * Per-request JWT claims beyond the role, which common-lib's {@code JwtAuthenticationFilter} doesn't
 * keep. Always read off the signed token, never request input: {@code insurerIds} bounds cross-tenant reads.
 */
public final class CallerContext {

    private static final ThreadLocal<Caller> CURRENT = new ThreadLocal<>();

    /**
     * @param insuredId  null for analysts and referentes
     * @param homeTenant the schema the token was issued for; {@link TenantContext#get()} may move away
     *                   from it (filing a claim runs in the tenant that issued the policy)
     */
    public record Caller(String insuredId, List<Long> insurerIds, String homeTenant) {

        public Caller {
            insurerIds = insurerIds == null ? List.of() : List.copyOf(insurerIds);
        }

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
