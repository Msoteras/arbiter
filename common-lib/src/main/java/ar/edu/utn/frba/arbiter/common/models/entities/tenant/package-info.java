/**
 * Entidades compartidas cuyas tablas viven en el <b>esquema de cada aseguradora</b>, no en
 * {@code arbiter_common}.
 *
 * <p>La distinción con el paquete padre importa y no es cosmética:
 *
 * <ul>
 *   <li>{@code common.models.entities} son las 10 tablas de {@code arbiter_common}: hay
 *       <b>una sola fila de cada una para toda la plataforma</b> (una aseguradora, un rol, un
 *       estado de expediente). Se leen con el {@code search_path} apuntando al esquema común.</li>
 *   <li>Este paquete son tablas <b>por tenant</b>: existe una copia dentro del esquema de cada
 *       aseguradora, y qué fila se lee depende del tenant resuelto para el request. La misma
 *       clase mapea tablas distintas según quién pregunte.</li>
 * </ul>
 *
 * <p>Por eso la regla general (CLAUDE.md) es que las tablas de tenant pertenecen al módulo dueño y
 * no a common-lib. Acá se hace excepción solo cuando <b>más de un módulo</b> necesita la misma
 * tabla. No sumes una entidad acá porque sí: si un solo módulo la usa, va en ese módulo. Las que
 * están, y por qué:
 *
 * <ul>
 *   <li>{@code Insured} — auth-service y cases-service la declaraban por separado con campos
 *       distintos, y ya habían divergido.</li>
 *   <li>{@code ClaimsAnalyst} — auth-service y cases-service, desde que la decisión del analista
 *       se resuelve del JWT.</li>
 *   <li>{@code Coverage} — cases-service es el dueño funcional, pero rules-service necesita su
 *       {@code branchId} para servirle al motor de clasificación los textos del referente: el
 *       motor solo tiene a mano un {@code coverageId}, y los textos se guardan por ramo.</li>
 * </ul>
 *
 * <p>Consecuencia operativa: leer cualquiera de estas entidades <b>sin un tenant resuelto</b> cae
 * al esquema común, donde la tabla no existe. Los jobs que corren fuera de un request tienen que
 * setear el {@code TenantContext} explícitamente, como hace
 * {@code ClassificationRefreshScheduler}.
 */
package ar.edu.utn.frba.arbiter.common.models.entities.tenant;
