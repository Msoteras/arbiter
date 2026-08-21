/**
 * Shared entities whose tables live in <b>each insurer's schema</b>, not in {@code arbiter_common}.
 *
 * <p>The distinction from the parent package matters and isn't cosmetic:
 *
 * <ul>
 *   <li>{@code common.models.entities} are the 10 tables of {@code arbiter_common}: there is
 *       <b>a single row of each for the whole platform</b> (one insurer, one role, one case
 *       status). They're read with the {@code search_path} pointing at the common schema.</li>
 *   <li>This package holds <b>per-tenant</b> tables: a copy exists inside each insurer's schema,
 *       and which row is read depends on the tenant resolved for the request. The same class maps
 *       different tables depending on who's asking.</li>
 * </ul>
 *
 * <p>That's why the general rule (CLAUDE.md) is that tenant tables belong to the owning module and
 * not to common-lib. The exception is made here only when <b>more than one module</b> needs the
 * same table. Don't add an entity here just in case: if a single module uses it, it goes in that
 * module. The ones that are here, and why:
 *
 * <ul>
 *   <li>{@code Insured} — auth-service and cases-service declared it separately with different
 *       fields, and they had already diverged.</li>
 *   <li>{@code ClaimsAnalyst} — auth-service and cases-service, ever since the analyst's decision
 *       is resolved from the JWT.</li>
 *   <li>{@code Coverage} — cases-service is the functional owner, but rules-service needs its
 *       {@code branchId} to serve the classification engine the referente's texts: the engine only
 *       has a {@code coverageId} at hand, and the texts are stored by branch.</li>
 * </ul>
 *
 * <p>Operational consequence: reading any of these entities <b>without a resolved tenant</b> falls
 * back to the common schema, where the table doesn't exist. Jobs running outside a request have to
 * set the {@code TenantContext} explicitly, the way {@code ClassificationRefreshScheduler} does.
 */
package ar.edu.utn.frba.arbiter.common.models.entities.tenant;
