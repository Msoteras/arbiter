# BD multi-tenant — estado y pendientes

Rama `feature/mapeo-der`. Documenta los scripts nuevos de base de datos y lo que queda
por hacer del lado del código.

**Estado:** la base está terminada y verificada. **El código todavía no está adaptado
a ella** — ver "Pendientes de código" al final.

---

## Los scripts

| Archivo | Qué hace |
|---|---|
| `db/init-multitenant.sql` | Crea los 5 esquemas, las 34 tablas y los catálogos. Solo `CREATE`, ningún `DROP`: está pensado para una base vacía y falla ruidosamente si no lo está. |
| `db/seed-demo.sql` | Datos de prueba repartidos entre las dos aseguradoras. Corre **después** del anterior. |

```bash
psql "$DATABASE_URL" -f db/init-multitenant.sql
psql "$DATABASE_URL" -f db/seed-demo.sql
```

Los scripts viejos (`db/init.sql`, `db/datos-aseguradoras.sql`) quedan sin tocar: son
el esquema único anterior. No los mezcles con estos.

---

## Los esquemas

| Esquema | Contenido |
|---|---|
| `arbiter_common` | 10 tablas: registro de tenants, identidad, RBAC y catálogos globales |
| `arbiter_bbva` | 24 tablas — datos de BBVA |
| `arbiter_provincia` | 24 tablas — datos de Provincia |
| `aseguradora_bbva` | 5 tablas — "BD Aseguradora" simulada de BBVA |
| `aseguradora_provincia` | 5 tablas — ídem Provincia |

**El esquema es el tenant.** No hay columna discriminadora en ninguna tabla de
aseguradora: `insurer` vive solo en el común y es el registro que dice qué tenants
existen (`insurer.schema_name` es la clave de ruteo).

### Qué va en el común y por qué

`insurer`, `users`, `user_insurer`, `role`, `permission`, `role_permission`,
`user_role`, `branch`, `claim_cause`, `case_status`.

- **Identidad compartida**: el login pasa antes de saber el tenant, así que `users` no
  puede estar adentro de un esquema de aseguradora.
- **Catálogos de plataforma**: ramas, hechos generadores y estados de expediente son
  iguales para todas. Lo que cada aseguradora acepta se restringe con sus propias filas
  de `document_requirement` y `coverage`, que sí son por tenant.

---

## Cómo consultarla

Las tablas del común no están duplicadas en cada tenant, así que hay dos opciones.

**Calificando el esquema** (explícito, sirve para cualquier cliente):

```sql
SELECT c.id, i.name, cs.name AS estado
  FROM arbiter_bbva.cases c
  JOIN arbiter_bbva.insured i     ON i.id = c.insured_id
  JOIN arbiter_common.case_status cs ON cs.id = c.current_status_id;
```

**Con `search_path`** (más cómodo para explorar; es lo que van a hacer los servicios
por request):

```sql
SET search_path TO arbiter_bbva, arbiter_common, public;

SELECT c.id, i.surname, cs.name
  FROM cases c
  JOIN insured i ON i.id = c.insured_id
  JOIN case_status cs ON cs.id = c.current_status_id;
```

El orden importa: resuelve primero en el tenant y cae al común. `public` va al final
porque ahí vive la extensión `vector`.

Para mirar la otra aseguradora, cambiás el primer elemento:

```sql
SET search_path TO arbiter_provincia, arbiter_common, public;
```

---

## Los datos de prueba

Repartidos así:

| | casos | asegurados | pólizas | análisis LLM | análisis de riesgo |
|---|---|---|---|---|---|
| `arbiter_bbva` | 5 | 6 | 6 | 3 | 3 |
| `arbiter_provincia` | 2 | 4 | 4 | 2 | 2 |

Los perfiles de riesgo vienen del seed original y están puestos a propósito para
ejercitar cada factor del scoring por separado:

| Asegurado | Aseguradora | Previos | Pagos | Para qué sirve |
|---|---|---|---|---|
| Martina Soteras | BBVA **y** Provincia | 0 | al día | Fast Track determinístico (LOW) + una identidad en dos tenants |
| Julián Pérez | BBVA | 3 | al día | `claim_frequency` saturado → HIGH |
| Lucas Martínez | BBVA | 0 | al día | Falta documentación, nunca scoreado |
| Nicolás Ferreyra | BBVA | 1 | al día | Punto intermedio de `claim_frequency` |
| Valeria Ríos | BBVA | 2 | al día | Ídem |
| Diego Sosa | BBVA | 0 | **en mora** | Aísla `policy_standing` de todo lo demás |
| Carla Gómez | Provincia | 0 | al día | Caso **resuelto**: recomendación del LLM + decisión del analista + notificación |
| Federico Aguirre | Provincia | 2 | **en mora** | Dos factores a la vez → CRITICAL |

Dos cosas que conviene mirar en la demo:

1. **El caso Fast Track no tiene fila en `llm_analysis`.** Es a propósito: `FAST_TRACK`
   lo decide el `FastTrackValidator`, el modelo nunca puede devolverlo (decisión #6), y
   el `CHECK` de la tabla lo impide. Se resuelve desde `cases.was_fast_track`.
2. **Martina es cliente de las dos aseguradoras.** Un `usuario` en el común, dos filas
   de `insured` en esquemas distintos, dos filas en `user_insurer`. Es el caso que
   muestra identidad compartida con datos aislados.

---

## Diferencias con el DER dibujado

El script sale de `docs/arbiter der.mdj`, pero se apartó en estos puntos, todos
acordados:

- **`factor` no se crea**: está dibujada sin tipos ni PK, es un enum. Los códigos viven
  en `RiskFactorIds` de `classification-service`.
- **`regla` no se crea**: no existe una regla común a todas las aseguradoras. Toda regla
  es de una aseguradora, así que `insurer_rule` es la única, y `insurer_rule_history` y
  `rule_result` cuelgan de ella.
- **`rama_aseguradora` y `hecho_generador_aseguradora` no se crean**: existían para
  colgar un `aseguradora_id`, que en multi-tenant por esquema no tiene sentido.
- **Todo `aseguradora_id` adentro de un esquema de tenant se eliminó**, por lo mismo.
- **`claim_cause` es única por `(branch_id, name)`**, no globalmente: el mismo hecho
  generador aplica a varias ramas.

Ojo con esto al abrir el `.mdj`: al momento de escribir esto **todavía tenía `regla` y
el `regla_id` de `regla_aseguradora`**. Si lo regenerás desde el modelo sin borrarlos,
vuelven a aparecer.

---

## Pendientes de código

Nada de esto está hecho. El código actual apunta al esquema único viejo y **no
funciona contra esta base**.

### 1. `ddl-auto: validate` en los 5 servicios

Hoy los cinco `application.yml` tienen `ddl-auto: update`. Con el `search_path`
apuntando al tenant, Hibernate **recrea las tablas de `arbiter_common` adentro de cada
esquema de aseguradora** apenas no las encuentra calificadas. Esto no es opcional.

### 2. Resolución del tenant por request

No existe todavía. Hace falta:

- Un claim de aseguradora en el JWT. `JwtService` hoy emite `rol`, `nombre`, `apellido`
  e `insuredId`, ninguno de aseguradora. El dato está en `arbiter_common.user_insurer`.
- Un `CurrentTenantIdentifierResolver` + `MultiTenantConnectionProvider` (o un
  interceptor que setee el `search_path` al inicio del request) que lo lea.
- Decidir qué pasa cuando un usuario pertenece a **más de una aseguradora** — Martina en
  el seed es exactamente ese caso. ¿El login pide cuál? ¿Se emite un token por
  aseguradora?

El orden importa: leer el perfil del usuario (nombre, apellido) requiere saber el
tenant, así que la aseguradora tiene que resolverse primero, en el login.

### 3. Auth

Con Auth0 ya integrado y `AUTH_PROVIDER=auth0` (hoy defaultea a `database`), el
`Auth0Adapter` **no necesita `password_hash`** — busca por email, valida contra Auth0 y
actualiza los contadores de bloqueo, y esas tres columnas existen. Ahí no hay nada que
hacer.

Lo que sí rompe es la entidad `User`: mapea siete columnas que ya no existen
(`password_hash`, `nombre`, `apellido`, `rol`, `sector`, `fecha_ingreso`, `insured_id`).
Con `ddl-auto: validate`, `auth-service` **no arranca**. De dónde sale cada una ahora:

| Campo | Origen nuevo |
|---|---|
| `rol` | join `user_role` → `role.code` |
| `nombre`, `apellido` | tabla de perfil del tenant (`insured` / `claims_analyst` / `insurer_referent`) |
| `insuredId` | `insured.dni` en el esquema del tenant |
| `password_hash`, `sector`, `fecha_ingreso` | se van, no están en el DER |

Eso arrastra a `JwtService`, que usa `getRol()`, `getNombre()` y `getApellido()`.

Detalle menor: `auth0_sub` está en la tabla pero el código no lo usa (`Auth0Adapter`
busca por email). Queda listo para migrar la búsqueda al `sub`, que es lo estable si
alguien cambia de mail.

### 4. Tablas que dejaron de existir

Las entidades que las mapean hay que rehacerlas:

| Antes | Ahora |
|---|---|
| `classification_log` | se parte en `llm_analysis` + `llm_reason` + `case_classification` |
| `image_embedding` | `image_analysis` (suma `fuente_externa`, `tipo_coincidencia`, `hash_externo`) |
| `rule`, `branch_insurer`, `insurer_claim_cause`, `factor` | eliminadas |

Y `cases` cambió de raíz: donde había texto libre (`branch`, `product`, `claim_cause`,
`insured_item`, `policy_number`, `insured_name`) ahora hay FKs, y el resultado de la
clasificación y el score se fueron a `case_classification` y `risk_analysis`.

---

## Notas de despliegue

- **`CREATE EXTENSION vector` es la primera sentencia del script** y va fuera del
  `BEGIN`. Probala antes de correr todo:

  ```bash
  psql "$DATABASE_URL" -c "SELECT name FROM pg_available_extensions WHERE name='vector';"
  ```

  Si devuelve 0 filas, el Postgres no tiene pgvector y hay que usar un template que lo
  traiga. Si tira `permission denied to create extension`, el usuario no es superuser y
  hay que crearla desde la consola de Railway.

- En Railway, para conectarte desde afuera usá `DATABASE_PUBLIC_URL`. El `DATABASE_URL`
  apunta a `*.railway.internal`, que solo resuelve dentro de su red.
