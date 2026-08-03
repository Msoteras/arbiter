# Reestructurar `Case` con FKs — plan de arranque

> **Estado al 2/8/2026 — la reestructuración está COMPLETA y con los tests en verde.**
> Paso 1 (`status` → FK) y paso 2 (el resto de las FKs) cerrados; ver esas secciones para el
> detalle. Lo que queda abierto está en "Lo que sigue".

Última pieza de la migración multi-tenant en `feature/mapeo-der`. Todo lo demás
(`PolicySnapshot`, `ImageAnalysis`, `ClassificationLog` → 3 tablas, resolver de tenant en los 5
módulos) ya está.

**La BD ya está lista.** `db/init-multitenant.sql` tiene `cases` con las 35 columnas finales,
verificado corriendo. No hay que tocar el esquema para arrancar.

---

## Qué cambia

`Case` deja de guardar texto libre y pasa a FKs:

| Hoy (texto libre) | Queda como |
|---|---|
| `branch` | vía `claim_cause.branch_id` |
| `product`, `policyNumber` | vía `policy` |
| `claimCause` | FK `claim_cause_id` → `arbiter_common.claim_cause` |
| `insuredItem` | `declared_item` (sigue siendo texto: es lo que declara el asegurado) |
| `insuredId` (DNI) | FK `insured_id` |
| `insuredName` | vía `insured` |
| `status` (enum `CaseStatus`) | **FK `current_status_id` → `arbiter_common.case_status`** |
| `eventDate` / `eventLocation` | `occurred_at` / `event_address` (+ `locality`, `province`, lat/long) |
| `createdAt` | `reported_at` |
| `pep`, `contactEmail`, `contactPhone`, `imageConsent` | pasan a `insured` |

Se quedan en `cases` (read model de la bandeja, ya en el esquema): `analysis_classification`,
`analysis_confidence`, `analysis_detail`, `risk_score`, `risk_band`, `risk_breakdown`,
`forensic_report`, `was_fast_track`, `classification_attempts`, `manual_adjustment_note`.

**El frontend no se toca.** La forma plana vive en `CaseResponse`, que se arma en un solo lugar
(`CaseServiceImpl.toResponse`, ~línea 197). Ese es el punto que aplana los joins.

---

## Lo que hace que esto sea grande: `status`

No es el volumen de campos, es que `status` pasa de enum a FK y eso toca la máquina de estados.
Catorce sitios, y estos cinco son los que importan:

| Archivo | Qué rompe |
|---|---|
| `services/CaseStatusService.java` | `getStatus()`/`setStatus()` y `transition()` — el ciclo de vida entero |
| `models/repositories/CaseRepository.java` | `findByStatus(CaseStatus)`, la query del poller |
| `models/repositories/CaseSpecifications.java` | el filtro de la bandeja |
| `services/ClassificationRefreshScheduler.java` | busca pendientes y marca `CLASSIFICATION_FAILED` |
| `services/CaseServiceImpl.java` | crea, transiciona y mapea |

**Sugerencia de enfoque:** hacer `status` primero y solo, con los tests en verde, antes de tocar
el resto de las FKs. Es el cambio riesgoso; los demás campos son mecánicos.

Una opción para acotarlo: que `Case` exponga `getStatus()`/`setStatus()` como métodos que
traducen entre el enum y la entidad `CaseState`, de modo que los 14 call sites sigan compilando
mientras la columna ya es FK. Después se van migrando de a uno. Evita el big bang.

---

## Paso 1 · `status` → FK (cerrado)

Hecho, con `mvn -pl cases-service -am test` en **87/87 verde** y `ClaimSecurityTest` de
classification en 6/6.

**Cómo quedó.** `Case.status` (enum) pasó a `Case.currentStatus` → `@ManyToOne` a
`arbiter_common.case_status` (`CaseState`, ya estaba en common-lib). El enum `CaseStatus` **no
se fue**: sigue siendo el vocabulario de la máquina de estados y de la API. La traducción vive
en `CaseStateCatalog` (cachea las 6 filas: son del esquema común, iguales para todo tenant e
inmutables en runtime). Se resolvió con getters derivados en vez de migrar los 14 call sites:

| Archivo | Qué cambió |
|---|---|
| `models/entities/Case.java` | `currentStatus` FK + `getStatus()` `@Transient` derivado. **No hay `setStatus`**: escribir estado es de `CaseStatusService` |
| `models/entities/CaseStatusHistory.java` | `fromStatus`/`toStatus` → FKs `initialStatus`/`finalStatus` + `getFromStatus()`/`getToStatus()` derivados (así `StatusTransitionResponse` y el frontend no se enteran) |
| `services/CaseStateCatalog.java` | **nuevo** — enum ↔ fila, con cache |
| `models/repositories/CaseStateRepository.java` | **nuevo** — `findByName` |
| `exceptions/UnknownCaseStateException.java` + handler | **nuevo** — 500: si falta una fila del catálogo, el seed del esquema común está incompleto |
| `services/CaseStatusService.java` | resuelve el destino por catálogo; suma `initialStatus()` para que `createCase` no toque el catálogo |
| `models/repositories/CaseRepository.java` | `findByCurrentStatusName(String)` + default `findByStatus(CaseStatus)` que delega — el poller y sus stubs quedaron intactos |
| `models/repositories/CaseSpecifications.java` | el filtro de la bandeja va por `currentStatus.name` |
| `services/CaseServiceImpl.java` | `createCase` usa `caseStatusService.initialStatus()` |

Tests tocados: `CaseServiceImplTest`, `CaseStatusServiceTest`, `ClassificationRefreshSchedulerTest`,
`ClassificationServiceClientTest`, `CaseRepositorySpecificationTests` (todos por el builder), más
el helper nuevo `test/support/CaseStates.java`. En classification, `ClaimSecurityTest` ahora
mockea `CaseOutcomeRepository` con `@MockitoBean`: `cases` vive en el esquema de cases-service y
el test mide RBAC, no persistencia.

**Cambio de esquema que salió de acá:** `case_status_history` no tenía columna `actor`, y el
frontend la usa (`status-timeline.component.ts`, etiquetas Asegurado/Analista/Sistema). Se agregó
`actor VARCHAR(20) NOT NULL` en `db/init-multitenant.sql`. **No está en el DER** — sumarlo. No
se puede derivar de `changed_by IS NULL`: las transiciones de SYSTEM no tienen usuario detrás.

**Quedó sin mapear a propósito** (nullable, sin código que las escriba todavía):
`case_status_history.observation` y `case_status_history.changed_by`.

---

## Resolver los strings a FKs al crear

`createCase` (`CaseServiceImpl:43`) recibe `CaseRequest` con strings y hoy los guarda tal cual.
Con FKs hay que resolverlos:

| String del request | Cómo se resuelve |
|---|---|
| `claimCause` + `branch` | `claim_cause` es único por `(branch_id, name)` → buscar branch por nombre, después la causa |
| `policyNumber` | `policy.external_policy_number` (es UNIQUE) |
| coverage | sale de `policy.coverage_id`, no viene en el request |
| `insuredId` (DNI) | `insured.dni` (es UNIQUE) |

**Decisión pendiente:** qué pasa cuando un string no matchea. Hoy nunca falla, se guarda texto
libre. Con FKs, una póliza que todavía no sincronizó desde la BD Aseguradora haría fallar el alta
de la denuncia. Opciones: rechazar con 422, o crear el expediente en un estado "pendiente de
resolución". **Esto hay que definirlo antes de escribir `createCase`.**

Nota: `Insured` vive hoy en `auth-service`. cases-service necesita leerlo — es tabla del esquema
de tenant, no del común, así que no aplica lo de `common-lib`. Ver si se lee por REST o se suma
una entidad de lectura.

---

## Otros consumidores

- `ClassificationServiceClient:64-69` arma el `ClaimReport` con los strings planos. El contrato
  con classification-service **no cambia**: sigue mandando strings, ahora resueltos desde los joins.
- `CaseServiceImplTest` y `ClassificationServiceClientTest` construyen `Case` con el builder viejo.

---

## Paso 2 · el resto de las FKs (cerrado)

Hecho, con los tests de cases-service en verde.

**Decisión tomada (Aylén, 2/8):** si un string no resuelve → **422**. El wizard del frontend solo
deja elegir pólizas ya cargadas, así que un valor que no matchea es un error real, no un caso a
tolerar. Vive en `CaseReferenceResolver` + `UnresolvedCaseReferenceException`.

| Antes (texto libre) | Ahora |
|---|---|
| `branch` | `claimCause.getBranch().getName()` — no hay columna |
| `product`, `policyNumber` | FK `policy` |
| `claimCause` | FK `claim_cause_id` |
| `insuredItem` | `declared_item` (sigue texto: es lo que declara el asegurado) |
| `insuredId`, `insuredName` | FK `insured` (DNI y `fullName()`) |
| `eventDate` / `eventLocation` | `occurred_at` / `event_address` |
| `createdAt` | `reported_at` |
| `pep`, `imageConsent`, contacto | pasan a `insured`, vía `applyDeclaredDetails` |

`CaseResponse` **no cambió** y el frontend tampoco: todo se aplana en `CaseServiceImpl.toResponse`.
El `ClaimReport` que va a classification-service sigue mandando los mismos strings, ahora leídos
de los joins.

Dos cosas que salieron de acá y no estaban en el plan:

- **`response_deadline`** era NOT NULL sin default. Es 30 días desde la denuncia (Ley 17.418
  art. 56) — constante `CaseServiceImpl.RESPONSE_TERM_DAYS`, no configuración de rules-service,
  porque lo fija la ley y no la aseguradora.
- **`deterministicFastTrack` mapeaba a `deterministic_fast_track`**, una columna distinta de la
  que classification-service escribe por JDBC (`was_fast_track`). O sea que el flag nunca llegaba.
  Corregido.

Entidad `Insured` nueva en cases-service (tabla de tenant, no va a common-lib). **Queda duplicada
con la de auth-service**, que es la dueña del alta — acá es solo lectura + refresco de los campos
declarativos de la denuncia.

Tests: `CaseServiceImplTest`, `CaseRepositorySpecificationTests`, `ClassificationServiceClientTest`,
`ClassificationRefreshSchedulerTest`, `CaseStatusLifecycleTests`, más `CaseReferenceResolverTest`
nuevo (los 422 y el ruteo de pep/consentimiento) y el fixture `test/support/CaseFixtures.java`.
`CaseControllerTest` no se tocó, como estaba previsto.

---

## Paso 3 · qué se denormaliza y qué se joinea (cerrado)

**El criterio (Aylén, 2/8): se denormaliza solo lo que se FILTRA. Lo que solo se muestra, se
joinea.** La bandeja filtra por estado, causa, póliza, asegurado, banda de riesgo, fecha y texto
libre — **no** por clasificación. Por eso salieron de `cases` cuatro columnas:
`analysis_classification`, `analysis_confidence`, `analysis_detail`, `risk_breakdown`. Quedan
`risk_score`/`risk_band` (se filtran), `forensic_report`, `was_fast_track`,
`classification_attempts` y `manual_adjustment_note`.

`CaseResponse` **no cambió**. Esos cuatro valores ahora los lee `CaseAnalysisRepository` desde
`llm_analysis` + `llm_reason` + `risk_analysis`, que viven en el **mismo esquema de tenant** que
`cases` — es la imagen espejo de `CaseOutcomeRepository`, que ya cruza el límite al revés. JDBC
plano y columnas nombradas, no entidades, para que ningún segundo módulo se declare dueño.

Dos cosas que hay que tener presentes de ese repositorio:

- Las dos tablas son **append-only**, así que "la clasificación" siempre es la fila más nueva:
  `DISTINCT ON (case_id) ... ORDER BY case_id, id DESC`.
- El acceso es **batcheado** (`findByCaseIds`). Pedirlo caso por caso desde `listCases` es el N+1
  que hace colapsar una bandeja paginada.

Detalles que se resolvieron de paso:

- **La guarda del poller** era `getAnalysisClassification() != null`, que ya no existe. Ahora mira
  el estado: el scheduler solo trae `PENDING_CLASSIFICATION`, y salir de ese estado es justamente
  lo que hace `refreshClassification` cuando llega el resultado.
- **Ventana de reclasificación:** como `llm_analysis` es append-only, la corrida vieja sigue siendo
  la última fila mientras se recalcula. `toResponse` no surface ninguna clasificación cuando el
  expediente está en `PENDING_CLASSIFICATION` — antes eso se lograba nuleando las columnas.
- **`case_classification.classification_attempts` nunca se escribía** (quedaba en 0). El contador
  vivo es `cases.classification_attempts`; su valor final ahora viaja en `AnalystDecisionRequest`
  (lo completa cases-service al reenviar, igual que `analystId`) y se congela en el registro
  auditable.

---

## Lo que sigue

En orden de lo que más importa:

1. **`ddl-auto: update` → `validate`.** El encabezado de `db/init-multitenant.sql` dice que con el
   `search_path` multi-tenant tiene que ser `validate`, "never `update`", porque en `update`
   Hibernate **recrea las tablas de `arbiter_common` dentro de cada esquema de tenant** apenas no
   las encuentra calificadas. Los 5 módulos hoy tienen `update`. Es la contradicción más cara que
   queda abierta: hace que el esquema real deje de ser el del script.
   Ojo: los ITs dependen de que `ddl-auto` cree las tablas en el contenedor, así que pasar a
   `validate` implica correr `init-multitenant.sql` en el Testcontainers.
2. ~~Columnas de `cases` sin mapear~~ — las 4 restantes ya están en la entidad:

   | Columna | Cómo quedó |
   |---|---|
   | `police_report_at` | Mapeada **y cableada**: nuevo campo opcional en `CaseRequest`, seteado en `createCase`. Único de los 4 con productor natural (el alta de la denuncia). |
   | `fraud_determined` | Mapeada, `boolean` default `false`. **Nadie la escribe** — no hay flujo de "marcar como fraude", solo aprobar/rechazar. Queda para cuando ese flujo exista. |
   | `destination` | Mapeada como `String` plano, **no enum**. El DER nunca define sus valores — ni siquiera en el `.mdj` está como enumeración, es un `VARCHAR(40)` suelto. Inventar valores acá sería fabricar reglas de negocio. Si el equipo tiene los valores reales (¿pago/rechazo/investigación?), avisar y se tipa. |
   | `scoring_configuration_id` | `Long` crudo, mismo patrón que `classification_id`: la tabla es de rules-service. Sin productor porque classification-service todavía no llama a rules-service (`MockRulesAdapter`). |

   `analyst_id` la cubre Flor en su historia. `classification_id` ya estaba mapeada **y poblada**
   desde el paso anterior: classification-service devuelve el id del `case_classification` que
   crea y cases-service lo guarda, así que el expediente queda ligado al `llm_analysis` que
   respaldó el veredicto.
3. ~~Actualizar el DER~~ — el `.mdj` ya está sincronizado salvo cuatro columnas de `expediente`
   que suma Aylén (`score_riesgo`, `banda_riesgo`, `reporte_forense`, `nota_ajuste_manual`).
4. ~~`Insured` duplicada~~ — movida a `common-lib`, en `common/models/entities/tenant/`.

---

## Para el DER

Sumar a `expediente` (ya están en el script, faltan en el `.mdj`):
`classification_attempts INTEGER NOT NULL`, `manual_adjustment_note TEXT`, y las siete del read
model (`analysis_classification`, `analysis_confidence`, `analysis_detail`, `risk_score`,
`risk_band`, `risk_breakdown`, `forensic_report`).

Y `image_consent BOOLEAN NOT NULL` va en `asegurado`, no en `expediente` — el script ya lo tenía
bien y **el código ya lo sigue** (paso 2: vive en `Insured`).

Sumar a `historial_estado_expediente`: `actor VARCHAR(20) NOT NULL` (ver Paso 1). Distinto de
`changed_by`, que es nullable porque las transiciones de SYSTEM no tienen usuario detrás.
