# Plan de pruebas — multi-tenant contra la BD real

Qué hay que verificar cuando se toca el esquema multi-tenant o las entidades, y qué se
encontró la primera vez que se corrió esto de verdad (3/8).

**Por qué existe este documento:** los tests de integración levantan un contenedor vacío y
arman el esquema desde las entidades, todo en `public`. Eso significa que **ningún test
detecta un desfasaje con el esquema real**, ni que una query dependa del `search_path`. Los 6
bugs de abajo pasaron los 116 tests en verde y aparecieron recién al levantar los módulos
contra Railway. Es el gap #1 del [handoff](handoff-multitenant.md).

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

### Verificado en vivo contra Railway (3/8)

| Qué | Resultado |
|---|---|
| Alta sobre la póliza de Provincia, logueada como Martina (login resuelve `arbiter_bbva`) | El expediente se persistió en **`arbiter_provincia`**, no en el tenant del login. |
| Martina lista sus expedientes | **4**: 3 de BBVA + 1 de Provincia, fusionados. |
| Ids repetidos entre esquemas | Aparecen **dos `#4`** (uno por aseguradora). Son autoincrementales por esquema: por eso el orden es por fecha de denuncia y el id sólo desempata. |
| Analista de BBVA lista expedientes | 5, **ninguno de Provincia**. Sin fuga entre tenants. |
| Martina pide un expediente ajeno (`#2`, de Julián Pérez) | **404**. |
| Martina pide uno propio (`#1`) | 200. |

---

## Lo que se encontró al correrlo por primera vez (3/8)

Los 6 pasaron los 116 tests en verde.

| # | Síntoma | Causa |
|---|---|---|
| 1 | Ningún módulo arranca: `missing table [branch]` | Las entidades viven entre `arbiter_common` y el esquema del tenant. La extracción de metadata por defecto (`grouped`) mira **un solo** esquema: el `current_schema()`, o sea el del tenant. Se resolvió con `jdbc_metadata_extraction_strategy: individually` en los 5 `application.yml` — sin tocar ninguna entidad. |
| 2 | `missing table [factor]`, `[insurer_claim_cause]` | Entidades que mapeaban tablas inexistentes en el modelo real. Se borraron. |
| 3 | `missing column [insurer_id]` en `insurer_rule` | Adentro de un esquema de tenant no va el id de aseguradora: el esquema **es** la aseguradora. Se sacó la asociación. |
| 4 | `wrong column type` en `cases.risk_score` | La columna es `numeric(4,3)`, la entidad la mapeaba como `Double`→`float(53)`. Se declaró el tipo JDBC y se mantuvo `Double` en Java, porque es lo que expone el DTO al frontend. |
| 5 | `GET /cases` → 500, `relation "llm_analysis" does not exist` | `CaseAnalysisRepository` usaba un `JdbcTemplate` sobre el `DataSource`, que pide conexiones **directo al pool** y nunca pasa por el `TenantConnectionProvider`: sus queries corrían sin el `search_path` del tenant. Ahora corren sobre la conexión de Hibernate. |
| 6 | `LazyInitializationException` en `Branch` | Con `open-in-view` apagado no hay sesión cuando se arma el DTO. Se puso `ClaimCause.branch` en EAGER. **No** se puede resolver con `@Transactional` en el service: el barrido multi-aseguradora cambia de esquema en medio del método y una transacción retiene una conexión, así que el `search_path` quedaría clavado en el primero. |
| 7 | Alta → 500, `null value in column "was_fast_track" violates not-null` | `Case.deterministicFastTrack` era un `Boolean` sin default contra una columna `NOT NULL DEFAULT false`; el default del esquema no salva porque Hibernate manda la columna en el INSERT igual. Se le puso `= false` (con `@Builder.Default`) y el reset de reclasificación pasó de `null` a `false` — para quien lee da lo mismo, `wasFastTracked()` ya trataba los dos casos igual. |

### La lección

Los cuatro primeros los detecta el arranque; el 5 y el 6 sólo aparecen **leyendo** por el
endpoint, y el 7 sólo **escribiendo**. Arrancar los módulos no alcanza, y leer tampoco: hay
que ejercitar el alta. Conviene además hacerlo con un usuario de cada rol, porque el camino del
asegurado (agregación entre esquemas) y el del analista (un solo esquema) no comparten código.

---

## Pendientes

- **Testcontainers no corre `init-multitenant.sql`** (gap #1 del handoff). Mientras siga así,
  esta verificación manual es la única red. Arreglarlo es lo que haría innecesario medio
  documento.
- **`RulesServiceApplicationTests` y `ReportsServiceApplicationTests`** son `@SpringBootTest`
  pelados que necesitan una base viva y hoy rompen `mvn test` en el reactor completo. Como esos
  módulos todavía no tienen código, no protegen nada: quedan excluidos hasta que lo tengan.
- **Alta de denuncia end-to-end** contra Railway: no se probó todavía. Necesita Ollama local
  levantado, si no la clasificación falla y el expediente queda en `PENDING_CLASSIFICATION`.
