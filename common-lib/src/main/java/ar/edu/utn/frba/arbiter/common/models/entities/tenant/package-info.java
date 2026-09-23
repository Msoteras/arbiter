/**
 * Shared entities whose tables live in <b>each insurer's schema</b>, not in {@code arbiter_common}:
 * the same class maps a different table depending on the tenant resolved for the request.
 *
 * <p>Tenant tables normally belong to their owning module; an entity goes here only when more than
 * one module needs it. Don't add one just in case.
 *
 * <p>Reading these without a resolved tenant falls back to the common schema, where the tables
 * don't exist: jobs running outside a request must set the {@code TenantContext} explicitly.
 */
package ar.edu.utn.frba.arbiter.common.models.entities.tenant;
