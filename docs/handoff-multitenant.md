# Handoff — migración multi-tenant y reestructuración de `Case`

Rama `feature/mapeo-der`. Fede: esto resume lo que cambió, por qué, y qué queda abierto.
El detalle paso a paso de `Case` está en [`case-restructuring-plan.md`](case-restructuring-plan.md);
acá va lo que necesitás saber para trabajar encima sin pisar decisiones ya tomadas.

> **Estado al 2/8 tarde-noche:** compila el reactor completo. La BD de Railway ya tiene el esquema
> nuevo cargado y verificado (`scripts/db-railway.ps1 verify`, los 14 chequeos en verde). Pero
> **arrancar los módulos contra esa base todavía NO funciona** — ver la sección 0, es lo primero
> que hay que resolver. Los tests de cases-service estaban en verde antes de las últimas dos
> tandas (owner check y multi-aseguradora), que siguen **sin correr**.

---

## 0. BLOQUEANTE: los módulos no arrancan contra Railway (`ddl-auto: validate`)

Esto es nuevo desde la última vez que se tocó este doc, y es lo primero que hay que mirar —
sin esto ni el backend ni el frontend tienen con qué hablar.

**Cómo se descubrió:** armé `docker-compose.railway.yml` para levantar los 5 módulos en Docker
contra Railway (dockerfiles existentes, sin tocar `docker-compose.yml` que sigue siendo el de
Postgres local). Al levantarlo, **4 de los 5 no arrancan.**

### Bug 1 — `getAnyConnection()` no seteaba `search_path` (parcialmente arreglado)

Hibernate valida el esquema al boot usando `MultiTenantConnectionProvider.getAnyConnection()`,
y en los 5 módulos esa conexión se devolvía cruda, sin `SET search_path`. Contra Railway el
default de Postgres es `public`, donde no hay ninguna tabla de la app — confirmado con
`reports-service`, que fallaba buscando `metric` (una tabla *por tenant*, ni siquiera del común).

Nunca se había visto porque los ITs con Testcontainers pisan `ddl-auto` a `update` contra una
base vacía donde todo cae en `public` sin estructura multi-schema — ese camino jamás ejercitó
`getAnyConnection()` de verdad.

**El intento de arreglo** (ya commiteado, commit `5ce89ff`, los 5 `TenantConnectionProvider.java`):
antes de devolver la conexión, consulta `arbiter_common.insurer` por el primer `schema_name`
activo y le aplica `search_path` — todos los esquemas de tenant son estructuralmente idénticos
(los crea la misma función `create_tenant_schema`), así que da igual cuál elija. Con `try/catch`
alrededor: si la query falla (base vacía antes del `init`, o el esquema plano de los ITs donde
`arbiter_common` no existe como schema), sigue con el comportamiento de antes. Compila y **no
rompió los tests que corrí** (`mvn clean test-compile` limpio; no llegué a correr el `test`
completo del reactor, quedó cortado).

**Resultado real, probando contra Railway con Docker:**

| Módulo | Con el fix |
|---|---|
| `reports-service` | ✅ arranca (`Started ReportsServiceApplication in 22.9s`) |
| `auth-service` | ❌ sigue `missing table [branch]` |
| `rules-service` | ❌ sigue `missing table [branch]` |
| `cases-service` | ❌ sigue `missing table [branch]` |
| `classification-service` | pasa la validación de esquema, falla por el Bug 2 (abajo) |

**No tengo explicación de por qué funciona para uno y no para los otros tres**, siendo el mismo
código en los 5 archivos. No llegué a investigarlo — hipótesis a probar: loguear qué devuelve la
query de `arbiter_common.insurer` en cada módulo al bootear (¿está tirando al `catch`? ¿por qué
ahí sí y en `reports-service` no?), o probar seteando `search_path` vía el connection string
(`DB_URL=...&currentSchema=arbiter_bbva,arbiter_common,public`) como alternativa más simple si
esto sigue sin ceder.

### Bug 2 — `classification-service`: referencia viva a una tabla que ya no existe

Distinto del anterior, y no tiene que ver con `search_path`. Pasa la validación de esquema y
explota después, en un bean init:

```
Caused by: org.postgresql.util.PSQLException: ERROR: relation "image_embedding" does not exist
```

`image_embedding` se partió en `image_analysis` en este mismo trabajo (ver sección 3 del doc
viejo / el commit de classification-service). El código de la entidad ya se actualizó, pero
**`PgVectorConfig.java` tiene SQL crudo hardcodeado contra el nombre viejo** — es el que ajusta la
dimensión del índice `ivfflat` en `@PostConstruct`. Localizado, no arreglado:

```
classification-service/src/main/java/ar/edu/utn/frba/arbiter/classification/config/PgVectorConfig.java
```

Buscar `image_embedding` ahí adentro y cambiarlo a `image_analysis` (dos apariciones: el
`pg_attribute` lookup y el `ALTER TABLE ... DROP COLUMN`).

### Estado de Docker ahora mismo

`docker-compose.railway.yml` (nuevo, corre los 5 módulos contra `DB_URL`/`DB_USER`/`DB_PASSWORD`
de `.env`, sin tocar el `postgres` local del compose original — tiene `name: arbiter-railway`
propio para no chocar de nombres con el otro stack, eso también costó un rato de debuggear).
Puede estar corriendo en tu Docker Desktop con contenedores reiniciándose en loop
(`restart: on-failure`) — no molesta pero conviene bajarlo mientras no se esté mirando:

```bash
docker compose -f docker-compose.railway.yml down
```

Para retomar donde quedó, una vez resuelto el Bug 1 de verdad:

```bash
docker compose -f docker-compose.railway.yml up -d --build
docker logs arbiter-railway-auth-service-1 --tail 30   # y así con cada uno
```

---

## 1. El esquema es la fuente de verdad, y ahora el código lo respeta

`ddl-auto` pasó de `update` a **`validate`** en los 5 módulos. No es cosmético: con el
`search_path` multi-tenant, en `update` Hibernate **recrea las tablas de `arbiter_common` adentro
de cada esquema de aseguradora** apenas no las encuentra calificadas. El encabezado de
`db/init-multitenant.sql` ya lo advertía.

Consecuencia práctica: **si una entidad no matchea el esquema, el módulo no arranca**. Ya
aparecieron dos casos, los dos iguales — columnas `JSONB` mapeadas como `TEXT`:

- `cases.risk_breakdown` y `cases.forensic_report`
- `risk_analysis.risk_breakdown`

Se arreglan con `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition = "jsonb"`, dejando el
converter como estaba. **Pueden quedar más**, sobre todo en auth/rules/reports, que no revisé
columna por columna. El error de Hibernate te dice tabla, columna y tipo esperado.

Los ITs pisan `ddl-auto` a `update` en su `AbstractPersistenceIT`, porque levantan contra un
contenedor vacío donde nadie corre el script. Eso tapa justamente este tipo de desfasaje: ver el
gap #1 más abajo.

### Scripts

| Archivo | Cuándo |
|---|---|
| `db/reset-multitenant.sql` | **Destructivo.** Dropea todos los esquemas `arbiter_*` y `aseguradora_*`. Sólo dev/Railway. |
| `db/init-multitenant.sql` | Crea esquemas, tablas y catálogos. Sólo `CREATE`: falla si la base no está vacía. |
| `db/seed-demo.sql` | Datos de prueba de las dos aseguradoras. |

Los tres, en ese orden. `init` no sirve para parchear: si el esquema cambió, hay que resetear.

---

## 2. Dónde vive cada entidad

Se consolidaron las entidades duplicadas en `common-lib`, y **hay dos paquetes distintos a
propósito**:

| Paquete | Qué es | Regla |
|---|---|---|
| `common.models.entities` | Las 10 tablas de `arbiter_common` | Una sola fila para toda la plataforma (una aseguradora, un rol, un estado) |
| `common.models.entities.tenant` | Tablas **por tenant** compartidas por más de un módulo | Existe una copia por aseguradora; qué fila se lee depende del tenant resuelto |

Hoy en `tenant/` sólo está `Insured`, que auth-service y cases-service declaraban por separado y
**ya habían divergido** (auth tenía `caseCount` nullable y no tenía `imageConsent`). El
`package-info` de ese paquete explica el criterio. **No sumes entidades ahí por las dudas:** si la
usa un solo módulo, va en ese módulo.

Los **repositorios no se comparten**: cada módulo declara el suyo apuntando a la entidad común.

---

## 3. `Case` dejó de tener texto libre

Lo que era `branch`, `product`, `claimCause`, `insuredItem`, `insuredId`, `insuredName`,
`policyNumber` ahora son FKs a `claim_cause`, `insured`, `policy`, `coverage`.

**`CaseResponse` no cambió y el frontend no se tocó.** Todo se aplana en un solo lugar,
`CaseServiceImpl.toResponse`. Si agregás un campo a la respuesta, ese es el punto.

Dos criterios que conviene no romper:

**Se denormaliza sólo lo que se FILTRA.** La bandeja filtra por estado, causa, póliza, asegurado,
banda de riesgo, fecha y texto libre. Por eso `risk_score`/`risk_band` son columnas de `cases`,
pero la recomendación del modelo, su confianza, sus motivos y el breakdown **no**: se joinean
desde `llm_analysis`/`llm_reason`/`risk_analysis` con `CaseAnalysisRepository`.

**Un string que no resuelve a FK falla con 422.** No se guarda texto libre ni se crea un
expediente "pendiente de resolución". El wizard sólo ofrece pólizas ya sincronizadas, así que un
valor que no matchea es un error real. Vive en `CaseReferenceResolver`.

### Detalles con trampa

- `llm_analysis` y `risk_analysis` son **append-only**: "la clasificación" siempre es la fila más
  nueva (`DISTINCT ON (case_id) ... ORDER BY id DESC`). Un lookup plano trae todas las corridas.
- Mientras un expediente está en `PENDING_CLASSIFICATION`, `toResponse` **no expone** la corrida
  anterior. Antes eso se lograba nuleando columnas; ahora hay que mirar el estado.
- Un **Fast Track no deja fila en `llm_analysis`** (el CHECK de la tabla rechaza `FAST_TRACK`), así
  que ahí la clasificación sale de `cases.was_fast_track`. Sin eso, todo Fast Track se vería como
  "sin clasificar".
- `case_classification.classification_attempts` no se escribía nunca. El contador vivo es
  `cases.classification_attempts`; su valor final viaja ahora en `AnalystDecisionRequest`, que lo
  completa cases-service al reenviar (el frontend no lo conoce), igual que `analystId`.

---

## 4. Multi-aseguradora: qué se resolvió y cómo

El caso Martina Soteras del seed (clienta de BBVA **y** Provincia) era el que rompía todo.

**El problema de fondo:** el `tenantSchema` del JWT se resuelve en el login, cuando todavía no se
sabe sobre qué póliza va a operar el usuario. `TenantResolver.primaryInsurerFor` agarraba la
primera aseguradora, así que la segunda era invisible.

Tres piezas, todas en cases-service:

| Clase | Qué hace |
|---|---|
| `CaseAccessPolicy` | Un `ASEGURADO` sólo lee **sus** expedientes. Analistas y referentes siguen sin restricción **dentro de su tenant**. |
| `InsuredCaseAggregator` | "Mis expedientes" junta los casos de **todas** las aseguradoras del asegurado. |
| `PolicyTenantLocator` | El alta corre en el esquema de la aseguradora que **emitió la póliza**, no en el del login. |

Y `CallerContext`, que guarda `insuredId`, `insurerIds` y `homeTenant` del token, poblado por el
`TenantResolvingFilter` que ya parseaba el JWT.

### Reglas que NO hay que romper acá

1. **Los esquemas sobre los que se itera salen SIEMPRE del claim firmado `insurerIds`**, nunca de
   un parámetro del request. Si eso se toma del pedido, cualquiera puede nombrar el esquema de
   otra compañía.
2. **Agregar es sólo para `ASEGURADO`.** Un analista de BBVA viendo casos de Provincia es una fuga
   entre tenants, no una feature.
3. **Cuando no sos el dueño se devuelve 404, no 403.** Un 403 confirma que el expediente existe, y
   los ids son secuenciales: alcanzaría para mapear la tabla probando.
4. **Si la operación se mueve de tenant, el token que se le manda a classification-service tiene
   que llevar el tenant real.** Reenviar el JWT del usuario haría que el expediente se guarde en
   una aseguradora y el `llm_analysis` en otra. Cuando `CallerContext.movedAwayFromHome()`, se
   firma un token de servicio con el tenant actual — mismo mecanismo que ya usaba el scheduler.
5. **Todo cambio de `TenantContext` se restaura en un `finally`.** Si no, la conexión vuelve al
   pool viendo el esquema equivocado y se lo lleva puesto el próximo request.

El agregador **pagina en memoria**, lo que sería inaceptable en la bandeja del analista y acá no
lo es: el universo son los siniestros propios de una persona. Ordena por fecha de denuncia — los
ids son autoincrementales *por esquema*, así que se repiten entre aseguradoras y no ordenan nada.

---

## 5. Gaps abiertos

**1 · Testcontainers no corre `init-multitenant.sql`.** Los ITs arman el esquema desde las
entidades que cada módulo conoce. Consecuencia concreta: `llm_analysis`/`risk_analysis` **no
existen** en el contenedor de cases-service, así que la lógica real de `CaseAnalysisRepository`
(el `DISTINCT ON`, el batch, el parseo del JSONB) no se ejercita nunca contra Postgres. Es el
mismo agujero que dejó pasar los dos mismatches de JSONB. Fix acotado: un IT que cree esas tres
tablas por SQL crudo. Fix de fondo: correr el script en el contenedor y validar ahí.

**2 · Las tres piezas de seguridad no tienen tests.** `CaseAccessPolicy`,
`InsuredCaseAggregator` y `PolicyTenantLocator` deciden qué datos ve quién entre aseguradoras, y
no tienen un solo test. El modo de falla es invisible: alguien viendo un expediente ajeno se ve
igual que viendo el propio. Es lo primero que haría.

**3 · `reports-service` y `rules-service` no resuelven tenant.** Tienen el scaffolding
(`TenantContext`, `TenantConnectionProvider`, `TenantIdentifierResolver`) pero **no** el
`TenantResolvingFilter`, así que hoy leen `arbiter_common`. No bloquea nada porque no están
implementados.

**4 · classification-service no llama a rules-service.** Sigue con `MockRulesAdapter`, reglas
hardcodeadas en memoria, sin `@Profile` (o sea, en todo entorno). Choca con la decisión 12 del
CLAUDE.md. Consecuencia visible: `cases.scoring_configuration_id` va a seguir en null, porque no
hay ninguna config real de la cual sacar el id.

**5 · Columnas mapeadas sin escritor.** `fraud_determined` (no hay flujo de "marcar como fraude",
sólo aprobar/rechazar) y `destination` (mapeada como `String` libre, **no** enum: ni el DER ni el
`.mdj` definen sus valores — está dibujada como un `VARCHAR(40)` suelto. Si alguien tiene los
valores reales, se tipa).

---

## 6. Para el DER — hecho

Ya sincronizado, incluidas las dos columnas que faltaban:

- `historial_estado_expediente.etiqueta_actor` VARCHAR(20) — equivale a `case_status_history.actor`
  del script. Distinto de `changed_by`, que es nullable porque las transiciones de SYSTEM no tienen
  usuario detrás; el frontend lo muestra en el timeline, no se puede derivar de `changed_by IS NULL`.
- `analisis_imagen.modelo_utlizado` VARCHAR(255) — equivale a `image_analysis.model`. El script lo
  tiene en VARCHAR(80); diferencia de longitud nada más, no de fondo.

Y lo que suma Aylén (`score_riesgo`, `banda_riesgo`, `reporte_forense`, `nota_ajuste_manual` en
`expediente`) también está.

---

## 7. Correr local contra Railway

```bash
cp .env.example .env     # completar; .env está gitignoreado
.\scripts\run-local.ps1 -CheckOnly
.\scripts\run-local.ps1 auth-service
```

Dos cosas que cuestan una hora si no las sabés:

- Railway da `DATABASE_PUBLIC_URL` como `postgresql://user:pass@host:port/base`. Spring necesita
  las tres partes separadas y el prefijo `jdbc:`, más `?sslmode=require`. **No uses
  `DATABASE_URL`**: apunta a `*.railway.internal` y sólo resuelve dentro de la red de Railway.
- `JWT_SECRET` no tiene default y tiene que ser **el mismo en los 5 módulos**: cada uno valida los
  tokens que emite auth-service.

Ollama va local, no a Railway. Sin él, el alta funciona pero la clasificación falla y el
expediente queda en `PENDING_CLASSIFICATION` hasta agotar reintentos.
