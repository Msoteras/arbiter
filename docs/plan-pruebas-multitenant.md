# Plan de pruebas — multi-tenant contra la BD real

Qué hay que verificar cuando se toca el esquema multi-tenant o las entidades, y qué se
encontró la primera vez que se corrió esto de verdad (9 bugs, todos con los tests en verde
antes de tocarlos).

**Por qué existe este documento:** los tests de integración levantan un contenedor vacío y
arman el esquema desde las entidades, todo en `public`. Eso significa que **ningún test
detecta un desfasaje con el esquema real**, ni que una query dependa del `search_path`, ni una
colisión de ids entre esquemas. Es el gap #1 del [handoff](handoff-multitenant.md), y es la
causa raíz de los 9 bugs de abajo.

---

## Cómo levantar el entorno

```bash
cp .env.example .env          # completar con DATABASE_PUBLIC_URL de Railway
.\scripts\run-local.ps1 -CheckOnly
.\scripts\run-local.ps1 auth-service
.\scripts\run-local.ps1 cases-service
```

`DB_URL` sale de `DATABASE_PUBLIC_URL` (no `DATABASE_URL`, que sólo resuelve dentro de Railway),
reescrita como `jdbc:postgresql://HOST:PUERTO/railway?sslmode=require`. El `JWT_SECRET` tiene
que ser el mismo en los 5 módulos.

Si hace falta rehacer la base, los tres scripts van en este orden — `init` falla si la base no
está vacía, por eso `reset` primero:

```bash
psql "$URL" -f db/reset-multitenant.sql    # DESTRUCTIVO
psql "$URL" -f db/init-multitenant.sql
psql "$URL" -f db/seed-demo.sql
```

---

## 1 · Arranque de los 5 módulos (lo más barato y lo que más encuentra)

Con `ddl-auto: validate`, **si una entidad no matchea el esquema, el módulo no arranca**. Eso
convierte el arranque en un test de esquema gratis. Levantar los 5, uno por uno, y esperar
`Started XApplication`.

Los 5 arrancan limpios contra Railway (verificado 2026-08-03): `auth-service`, `cases-service` y
`classification-service` tras los 9 bugs de la sección de abajo; `rules-service` y
`reports-service` no tenían pendiente ninguno de esos bugs, pero **nunca se habían arrancado
contra el esquema real** — el handoff avisaba que podían tener el mismo mismatch de JSONB que
`cases`/`risk_analysis`. No lo tienen: arrancaron sin tocar una línea.

Los errores hablan claro:

| Mensaje | Qué significa |
|---|---|
| `missing table [x]` | La entidad mapea una tabla que no existe. Sobra la entidad, o falta en el script. |
| `missing column [x]` | Falta la columna, o la entidad tiene un campo de más. |
| `wrong column type ... found [numeric], but expecting [float(53)]` | El tipo Java no matchea. Se resuelve con `@JdbcTypeCode`, sin cambiar el tipo que expone el DTO. |

## 2 · Login y resolución de tenant

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"analista.arbiter@gmail.com","password":"analista.arbiter123"}'
```

El JWT tiene que traer `tenantSchema` e `insurerIds`. Decodificar el payload y mirarlos: son
los que atan cada request a su aseguradora.

| Usuario | Esperado |
|---|---|
| `analista.arbiter@gmail.com` | `insurerIds:[1]`, `tenantSchema:"arbiter_bbva"` |
| `asegurado.arbiter@gmail.com` (Martina) | `insurerIds:[1,2]` — el caso multi-aseguradora |
| `referente.arbiter@gmail.com` | `insurerIds:[1]` |

Contraseñas: `<rol>.arbiter123`. Validan contra Auth0 real, no contra la base.

## 3 · Lectura de expedientes

```bash
curl -s "http://localhost:8083/api/v1/cases?page=0&size=20" -H "Authorization: Bearer $TOKEN"
curl -s "http://localhost:8083/api/v1/cases/4" -H "Authorization: Bearer $TOKEN"
```

**Ojo con los nombres de los campos** — la clasificación viaja como `analysisClassification` /
`analysisConfidence` / `analysisDetail`, no como `classification`. Leer la clave equivocada da
`undefined` y parece un bug que no existe (me pasó).

Qué mirar, además del 200:

- `analysisClassification` no vacío en un caso que tenga fila en `llm_analysis` — eso prueba
  que el join contra las tablas del tenant funcionó. Con el seed: los casos **2, 3 y 4** de
  `arbiter_bbva` tienen análisis.
- `branch`, `claimCause`, `insuredName` resueltos: prueban que las FKs (que reemplazaron al
  texto libre) se navegan bien.
- Un caso Fast Track **no** tiene fila en `llm_analysis` y su clasificación sale de
  `cases.was_fast_track`. Que aparezca vacío ahí no es un bug.

## 3.1 · Reclasificar (subir documentación faltante)

Un expediente en `AWAITING_DOCUMENTATION` al que se le suben los documentos vuelve a
clasificarse. Si el resultado nuevo es **Fast Track**, hay que mirar dos cosas a la vez:

- `analysisClassification` tiene que pasar a `FAST_TRACK` (no quedarse en el veredicto
  anterior).
- `status` tiene que pasar a `PENDING_ANALYST_REVIEW` (no quedarse en
  `AWAITING_DOCUMENTATION`).

Ver bug #8 más abajo — las dos cosas fallaban juntas, y de las dos formas hace parecer que el
sistema clasificó mal cuando en realidad clasificó bien y mostró mal.

## 4 · Aislamiento entre aseguradoras

Lo más importante y lo que peor falla: **cuando se rompe, no se ve** — un expediente ajeno se
muestra igual que uno propio.

- Martina (`insurerIds:[1,2]`) ve sus expedientes de **todas** sus aseguradoras.
- Un analista de BBVA **no** puede ver un expediente de Provincia. Si lo ve, es una fuga.
- Pedir un expediente ajeno devuelve **404, no 403** (un 403 confirma que existe, y los ids
  son secuenciales).
- Dar de alta una denuncia sobre una póliza de Provincia tiene que escribir en
  `arbiter_provincia`, aunque el login haya resuelto `arbiter_bbva`.

Los tests unitarios de esto están en `CaseAccessPolicyTest`, `InsuredCaseAggregatorTest` y
`PolicyTenantLocatorTest` (19 casos).

**Ojo con el seed:** de fábrica Martina tiene 3 expedientes en BBVA y **0 en Provincia**, así
que leer sola no demuestra que la agregación fusione dos esquemas — devuelve 3 igual. Para
probarlo hay que darle de alta una denuncia sobre `POL-CEL-2026-777`, su póliza de Provincia.
Eso además ejercita `PolicyTenantLocator`, que es lo que decide en qué esquema se escribe.

### Verificado en vivo contra Railway

| Qué | Resultado |
|---|---|
| Alta sobre la póliza de Provincia, logueada como Martina (login resuelve `arbiter_bbva`) | El expediente se persistió en **`arbiter_provincia`**, no en el tenant del login. |
| Martina lista sus expedientes | **6**: 5 de BBVA + 1 de Provincia, fusionados. |
| Ids repetidos entre esquemas | Aparecen **dos `#4`** (uno por aseguradora). Son autoincrementales por esquema: por eso el orden es por fecha de denuncia y el id sólo desempata. |
| Analista de BBVA lista expedientes | 7, **ninguno de Provincia**. Sin fuga entre tenants. |
| Martina pide un expediente ajeno (`#2`, de Julián Pérez) | **404**. |
| Martina pide uno propio (`#1`) | 200. |
| Alta con documentación adjunta desde el vamos | El gate resolvió **Fast Track** directo, sin pasar por `FALTA_DOCUMENTACION`. |
| Ciclo completo con Ollama real | `PENDING_CLASSIFICATION` → `PENDING_ANALYST_REVIEW` con `FAST_TRACK`, visible en la bandeja del analista. |
| `GET /cases/4?aseguradora=bbva` vs. `?aseguradora=provincia` | Devuelven expedientes **distintos y correctos** (iPhone 16 Pro / Samsung Galaxy S24). |
| Analista de BBVA pidiendo `?aseguradora=provincia` | **404** — el parámetro no alcanza a nombrar un tenant ajeno, se valida contra `insurerIds` del token. |
| Frontend: abrir el "Siniestro #4" de Provincia desde la lista | Abre el expediente correcto (antes abría el de BBVA). |
| Analista y referente de BBVA listan/leen expedientes tras el cambio de `CaseResponse` | 200, `insurerSlug`/`insurerName` en `null` (vista mono-tenant, es lo esperado — solo el asegurado multi-aseguradora los recibe poblados), sin regresión. |
| Analista de BBVA pide `/cases/4?aseguradora=provincia` (caso ajeno) | **404** — mismo resultado que sin el parámetro. |

---

## Lo que se encontró al correrlo por primera vez (9)

Todos con los 116 tests en verde antes de tocarlos.

| # | Síntoma | Causa |
|---|---|---|
| 1 | Ningún módulo arranca: `missing table [branch]` | Las entidades viven entre `arbiter_common` y el esquema del tenant. La extracción de metadata por defecto (`grouped`) mira **un solo** esquema: el `current_schema()`, o sea el del tenant. Se resolvió con `jdbc_metadata_extraction_strategy: individually` en los 5 `application.yml` — sin tocar ninguna entidad. |
| 2 | `missing table [factor]`, `[insurer_claim_cause]` | Entidades que mapeaban tablas inexistentes en el modelo real. Se borraron. |
| 3 | `missing column [insurer_id]` en `insurer_rule` | Adentro de un esquema de tenant no va el id de aseguradora: el esquema **es** la aseguradora. Se sacó la asociación. |
| 4 | `wrong column type` en `cases.risk_score` | La columna es `numeric(4,3)`, la entidad la mapeaba como `Double`→`float(53)`. Se declaró el tipo JDBC y se mantuvo `Double` en Java, porque es lo que expone el DTO al frontend. |
| 5 | `GET /cases` → 500, `relation "llm_analysis" does not exist` | `CaseAnalysisRepository` usaba un `JdbcTemplate` sobre el `DataSource`, que pide conexiones **directo al pool** y nunca pasa por el `TenantConnectionProvider`: sus queries corrían sin el `search_path` del tenant. Ahora corren sobre la conexión de Hibernate. |
| 6 | `LazyInitializationException` en `Branch` | Con `open-in-view` apagado no hay sesión cuando se arma el DTO. Se puso `ClaimCause.branch` en EAGER. **No** se puede resolver con `@Transactional` en el service: el barrido multi-aseguradora cambia de esquema en medio del método y una transacción retiene una conexión, así que el `search_path` quedaría clavado en el primero. |
| 7 | Alta → 500, `null value in column "was_fast_track" violates not-null` | `Case.deterministicFastTrack` era un `Boolean` sin default contra una columna `NOT NULL DEFAULT false`; el default del esquema no salva porque Hibernate manda la columna en el INSERT igual. Se le puso `= false` (con `@Builder.Default`) y el reset de reclasificación pasó de `null` a `false` — para quien lee da lo mismo, `wasFastTracked()` ya trataba los dos casos igual. |
| 8 | Reclasificar a Fast Track deja el expediente mostrando el veredicto **anterior** (`FALTA_DOCUMENTACION`) y sin pasar a `PENDING_ANALYST_REVIEW` | `llm_analysis` es append-only y Fast Track, por diseño (constraint incluido), no deja fila ahí. `cases-service` y `classification-service` preguntaban primero "¿hay fila de análisis?" en vez de "¿fue fast-track?", así que la fila vieja (que siempre existe) ganaba siempre. Se invirtió el orden: primero `wasFastTracked()`/`outcome.wasFastTrack()`, después el join. Es el más peligroso de los 9: no rompe nada, **muestra mal** — sólo se ve armando un caso real de reproceso con datos de más de una aseguradora. |
| 9 | El segundo expediente `#4` (el de Provincia) no se podía abrir desde el listado del asegurado — abría el `#4` de BBVA | Los ids son autoincrementales **por esquema**, así que colisionan entre aseguradoras. El link sólo llevaba el id. Se agregó un identificador de aseguradora a la URL — no el `insurerId` numérico crudo (se descartó por exponer una PK de la base en la URL sin necesidad), sino un slug legible derivado del `schema_name` (`InsurerSlug`, ej. `provincia`), validado en el backend contra los `insurerIds` firmados en el JWT. Un id/slug ajeno sigue devolviendo 404, igual que antes. |

### La lección

Los primeros cuatro los detecta el arranque; el 5 y el 6 sólo aparecen **leyendo** por el
endpoint; el 7 sólo **escribiendo**. El 8 y el 9 son otra categoría: no rompen nada, no dan
error — **muestran mal**, y sólo se ven con datos reales de más de una aseguradora (un mismo
asegurado con casos en dos esquemas, ids que colisionan, un reproceso real a Fast Track). Arrancar
los módulos no alcanza, leer tampoco, escribir tampoco: hace falta un dataset multi-aseguradora de
verdad y recorrer el ciclo completo (alta → reproceso → reclasificación) para encontrarlos.
Conviene además hacerlo con un usuario de cada rol, porque el camino del asegurado (agregación
entre esquemas) y el del analista (un solo esquema) no comparten código.

---

## Gaps del handoff cerrados esta sesión

- **Gap #1 (Testcontainers no corre `init-multitenant.sql`)** — cerrado para
  `llm_analysis`/`llm_reason`/`risk_analysis`: `CaseAnalysisRepositoryTests` crea esas tres tablas
  por SQL crudo en el contenedor (mismo shape que `db/init-multitenant.sql`) y ejercita el
  `DISTINCT ON`, el parseo de JSONB y el join de factores contra Postgres real — verificado con
  mutación (invertir el `ORDER BY` de "la corrida más reciente" hace fallar el test). 121 tests en
  verde. Sigue abierto en general (el resto de las tablas de tenant no se crean en el contenedor),
  pero la parte que motivó el gap —la lógica de `CaseAnalysisRepository`, la que rompió en Bug
  #5— ya tiene su red.
- **Gap #3 (`reports-service`/`rules-service` no resuelven tenant)** — cerrado: los dos módulos
  tienen ahora `TenantResolvingFilter` + `SecurityConfig`, mismo patrón que `auth-service`. Sin
  `CallerContext` (ninguno de los dos tiene todavía lógica multi-aseguradora que la necesite).
  Verificado en vivo: sin token → 401, con token contra un path sin controller → 404,
  `/v3/api-docs` público → 200 (en `rules-service`; `reports-service` no tiene `springdoc`
  agregado, gap preexistente y menor, no de esta sesión).

## Pendientes

- **Gap #4 (`classification-service` no llama a `rules-service`)** — sigue abierto, pero se partió
  en dos mitades de tamaño muy distinto (investigado 2026-08-03, no se codeó nada):
  - **Mecánica, sin decisión de diseño pendiente:** `FactorWeight`/`ScoreBand`
    (`ScoringConfiguration`) y `DocumentRequirement` calzan 1:1 con `BusinessRules.scoringConfig` y
    `requiredDocumentTypes` — los Javadocs de esas entidades ya lo dicen. Falta el controller, el
    service que arma el mapeo, y datos semilla (`db/seed-demo.sql` no tiene una sola fila en
    ninguna de las 5 tablas de `rules-service`).
  - **Sin diseñar todavía:** `InsurerRule` (`effect`, `ruleType`, `configuration` JSON de forma no
    documentada) no tiene mapeo obvio a `rules`/`exclusions`/`fastTrackCriteria`/
    `fastTrackThresholds` (hoy texto libre / objeto numérico en `MockRulesAdapter`). La tabla
    `rule_result` del DER (`result`, `evaluated_value`, `score_contribution`) sugiere que
    `InsurerRule` apunta a un motor de reglas evaluable que no existe en ningún lado del código.
    Necesita definición del equipo antes de codear, no es una decisión que se pueda tomar sola.
- **Gap #5 (columnas mapeadas sin escritor: `fraud_determined`, `destination`)** — **fuera de
  alcance de este PR** (confirmado con Aylén, 2026-08-03): sin datos reales de `destination` ni
  definición del flujo de `fraud_determined`, y probablemente surja de otras historias futuras
  cuando haya pruebas reales que lo motiven.
- **`RulesServiceApplicationTests` y `ReportsServiceApplicationTests`** son `@SpringBootTest`
  pelados que necesitan una base viva y hoy rompen `mvn test` en el reactor completo. Como esos
  módulos todavía no tienen endpoints reales, no protegen nada: quedan excluidos hasta que los
  tengan.
