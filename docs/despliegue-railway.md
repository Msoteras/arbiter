# Despliegue en Railway

Estado: **preparado, sin desplegar todavía.** Este documento es el procedimiento; lo que ya está
hecho del lado del repo se marca como tal.

La BD **no** va en Railway: va en **Supabase** (ver "Base de datos" abajo).

---

## Topología

Seis servicios de Railway, todos desde el mismo repo. **Solo uno tiene dominio público.**

| Servicio Railway | Root Directory | Config as code | Público | Notas |
|---|---|---|---|---|
| `arbiter-frontend` | `/` | `arbiter-frontend/railway.json` | **Sí** | Sirve la SPA y hace de reverse proxy de `/api/v1/*` |
| `auth-service` | `/` | `auth-service/railway.json` | No | |
| `rules-service` | `/` | `rules-service/railway.json` | No | |
| `classification-service` | `/` | `classification-service/railway.json` | No | |
| `cases-service` | `/` | `cases-service/railway.json` | No | |
| `clip-embedding` | `embedding-service` | `embedding-service/railway.json` | No | **Root Directory distinto**: su Dockerfile copia con rutas relativas a esa carpeta |

`reports-service` **no se despliega**: hoy no tiene ni un controller. Tiene Dockerfile y
`application.yml` al día, así que sumarlo después es crear el servicio y nada más.

### `clip-embedding`: por qué el Config File Path lleva la ruta completa

**El campo "Config File Path" nunca sigue el Root Directory** — es lo único documentado sin
ambigüedad por Railway: *"The Railway Config File does not follow the Root Directory path. You
have to specify the absolute path (...), for example: /backend/railway.toml"* — así que aunque el
Root Directory de este servicio sea `embedding-service`, ese campo del dashboard tiene que llevar
igual `embedding-service/railway.json`, no `railway.json` a secas. Ya está así en la tabla; queda
anotado para que nadie lo "simplifique" después pensando que es redundante.

El `dockerfilePath: "Dockerfile"` (relativo) *adentro* de ese `railway.json` sí es correcto tal
como está — pero eso Railway no lo documenta con la misma claridad, así que se verificó a mano en
vez de asumirlo: un build con el contexto acotado a `embedding-service/` únicamente (lo que Root
Directory produce, según su propia doc: *"Railway will only pull down files from that
directory"*) compila limpio y el contenedor levanta respetando `$PORT`. Si algún día Railway
cambia este comportamiento, el signo va a ser un build que falla buscando `Dockerfile` en la raíz
del repo en vez de en `embedding-service/`.

Que los backends no tengan dominio público es deliberado: se llegan solo por la red privada
(`*.railway.internal`), y lo único expuesto a internet es el frontend. Eso es lo que reemplaza al
Nginx del documento de arquitectura como terminador de TLS — Railway termina TLS, y el Nginx que
sigue existiendo es el que va **adentro** de la imagen del frontend, haciendo el ruteo por path.

### Por qué el Nginx quedó adentro del frontend

El frontend usa rutas relativas (`apiBaseUrl: '/api/v1'`, ver `src/environments/environment.ts`) y
nunca hardcodea host ni puerto. Alguien tiene que traducir `/api/v1/rules` → `rules-service`. En
desarrollo lo hace `proxy.conf.json`; en el despliegue lo hace `arbiter-frontend/nginx.conf.template`.

> **Las dos tablas de ruteo tienen que seguir sincronizadas.** Una ruta agregada a
> `proxy.conf.json` y olvidada en el template anda perfecto en desarrollo y da 404 apenas se
> despliega. Hoy están iguales (9 rutas).

---

## Base de datos — Supabase

El esquema es el de siempre: `db/init-multitenant.sql` (solo `CREATE`, falla si la base no está
vacía) y después `db/seed-demo.sql`. Sobre una base nueva de Supabase corren limpios.

Tres cosas propias de Supabase a tener en cuenta al armar el `DB_URL`:

1. **pgvector.** El esquema arranca con `CREATE EXTENSION IF NOT EXISTS vector`. Supabase lo trae
   disponible, pero hay que habilitarlo (Database → Extensions) si el `CREATE EXTENSION` falla por
   permisos.
2. **Pooler vs. conexión directa.** Supabase expone el pooler (Supavisor) en el **6543** y la
   conexión directa en el **5432**. El pooler en modo *transaction* rompe los prepared statements
   que usa el driver JDBC; si se usa ese puerto hay que agregarle `&prepareThreshold=0` a la URL.
   Para arrancar, lo más simple es el **5432**.
3. **SSL obligatorio**: `?sslmode=require` en la URL, igual que ya se usa con Railway.

Las migraciones manuales de `db/migrate-*.sql` **no** hacen falta sobre una base nueva: ya están
incorporadas a `init-multitenant.sql`. Solo aplican a bases que ya existían.

### Verificar que quedó consistente

No hay Flyway/Liquibase, así que no hay una fuente automática de verdad de "esto ya se aplicó" —
son 12 archivos sueltos (`db/migrate-*.sql` + `db/migrations/*.sql`) sin tabla de control. Antes de
dar por buena la base nueva (o para chequear la de Railway mientras siga en pie), corré:

```bash
python scripts/check-schema-consistency.py
```

Compara tabla por tabla y columna por columna lo que `db/init-multitenant.sql` define contra lo
que la base tiene de verdad (lee `DB_URL`/`DB_USER`/`DB_PASSWORD` de `.env`, arranca un cliente
`psql` descartable vía Docker). **Solo mira estructura** — no tipos, constraints, índices, ni las
migraciones que no son un simple `ADD COLUMN` (un `DELETE`, un índice `UNIQUE` que puede fallar en
silencio si hay duplicados). Esas se siguen confirmando a mano, una por una — el script lo recuerda
al final si encuentra algo.

Corrido contra Railway (28/08): estructura consistente salvo una, real y ya resuelta —
`policy_snapshot.total_amount_claimed` existía en las dos bases de tenant sin estar en
`init-multitenant.sql`. Era drift puro, sin dueño: ninguna entidad JPA la mapea, ningún SQL del
repo la toca. El monto total reclamado histórico existe como concepto, pero es del **siniestro**,
no de la póliza — `InsurerDatabaseAdapter.getHistory()` lo calcula al vuelo sumando
`aseguradora_*.siniestro_historico.monto_indemnizado`, tabla que ya estaba completa en
`init-multitenant.sql` y con datos realistas en `seed-demo.sql` (los 3 siniestros de Julián Pérez,
los 2 de Federico Aguirre). Esta columna era una versión mal ubicada de lo mismo. Se dropeó de
Railway con `db/migrate-drop-policy-snapshot-total-amount-claimed.sql`; una Supabase nueva armada
desde `init-multitenant.sql` directamente nunca la va a tener.

---

## Variables de entorno

**La fuente de verdad de qué necesita cada servicio es `docker-compose.railway.yml`**, que ya las
declara módulo por módulo. En Railway se cargan las mismas, con estas diferencias:

| Variable | En Railway |
|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | Apuntan a Supabase, no a Railway |
| `RULES_SERVICE_URL` | `http://rules-service.railway.internal:8081` |
| `CLASSIFICATION_SERVICE_URL` | `http://classification-service.railway.internal:8082` |
| `EMBEDDING_SERVICE_URL` | `http://clip-embedding.railway.internal:8000` |
| `LLM_PROVIDER` | **`gemini`** — ver abajo |
| `OLLAMA_BASE_URL` | No se setea: no hay Ollama en Railway |
| `JWT_SECRET` | **El mismo valor en los 5 servicios.** Si difieren, los tokens de servicio entre módulos se rechazan y el síntoma es un 401 sin explicación |
| `PASSWORD_ENCRYPTION_PRIVATE_KEY` | Ver abajo |
| `PORT` | La inyecta Railway sola. No setearla a mano |

El frontend además necesita las cuatro `*_SERVICE_URL` que consume su Nginx (ya vienen con default
en su Dockerfile, apuntando a los nombres `.railway.internal` de la tabla de arriba).

### `LLM_PROVIDER=gemini` es una desviación consciente

La **decisión de arquitectura #1** dice LLM en infraestructura propia con Ollama, sin APIs externas.
Ollama no entra en Railway: el propio `docker-compose.railway.yml` le reserva `mem_limit: 14g` y
aclara que corre al lado, no en Railway.

Se acordó arrancar con Gemini para las primeras pruebas y volver a Ollama después. **Es deuda
explícita, no un cambio de decisión.** Para volver a Ollama hay que levantarlo en una máquina con
GPU fuera de Railway y apuntarle `OLLAMA_BASE_URL` — el código ya soporta las dos vías y se cambia
con una variable.

### `PASSWORD_ENCRYPTION_PRIVATE_KEY`

Si no está seteada, `auth-service` genera un par RSA efímero en cada arranque (ver
`PasswordCipher`). Con una sola instancia no molesta; con más de una, el browser cifra contra la
clave de una instancia y el login puede caer en otra, fallando de forma intermitente. Solo aplica
con `AUTH_PROVIDER=database`; con Auth0 (el default) no interviene.

---

## Cuándo despliega (solo `main`)

Railway despliega por sí solo al detectar un push en la rama que tenga configurada. **En cada uno
de los 6 servicios**: Settings → Source → *Branch* = `main`. Sin eso, el default es la rama por
defecto del repo y cualquier push a `develop` saldría a producción.

Con eso, el flujo queda: se trabaja en `develop`, y **el merge del PR `develop` → `main` es lo que
dispara el despliegue** de los 6 servicios.

Además, en Settings → *Wait for CI*: activado. Railway espera a que el workflow de GitHub Actions
(`.github/workflows/ci.yml`) termine en verde antes de construir. El workflow corre en el push a
`main` justamente para eso, y como el gate vive en GitHub no hace falta guardarle a Railway ningún
token del repo.

El CI **no despliega**: es solo el gate. Quien despliega es Railway. Esa separación es a propósito
— no hay credenciales de Railway dando vueltas en los secrets de GitHub.

## Quién puede mergear a `main`

Como el merge a `main` es lo que dispara el despliegue, `main` necesita protección. Hoy **no tiene
ninguna** y hay que agregarla.

Esto **no es un hook de git**: los hooks son locales, no saben nada de PRs ni de aprobaciones y se
saltean con `--no-verify`. Es *branch protection*, que corre del lado de GitHub. Y **la tiene que
aplicar alguien con rol de admin en el repo** — al día de hoy, solo `Msoteras`.

Con exigir **1 aprobación** alcanza para lo que se busca: GitHub no permite aprobar tu propio PR,
así que el que abre el PR nunca puede ser quien lo aprueba.

```bash
gh api repos/Msoteras/arbiter/branches/main/protection -X PUT --input - <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "Backend (Java 21)",
      "Frontend (Angular 20)",
      "Ruteo dev == ruteo prod",
      "PR a main solo desde develop"
    ]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": {
    "required_approving_review_count": 1,
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": false
  },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
JSON
```

Qué hace cada cosa:

- **`required_approving_review_count: 1`** — lo que pediste: alguien distinto del autor tiene que
  aprobar.
- **`contexts`** — los tres jobs de `.github/workflows/ci.yml` más el de
  `.github/workflows/guard-main.yml`, que rechaza cualquier PR a `main` que no venga de `develop`.
  Los nombres tienen que coincidir exactos con el `name:` de cada job, o GitHub espera para
  siempre un check que nunca llega.

  > Ese último check es lo que hace cumplir "todo pasa por develop". El workflow por sí solo no
  > impide abrir el PR: lo deja en rojo. Si no está en esta lista, es una advertencia que
  > cualquiera puede ignorar al mergear.
  >
  > Contra: también bloquea un hotfix directo a `main`. Con `enforce_admins: false` la admin puede
  > saltearlo en una emergencia; si eso pasa seguido, conviene aceptar además `hotfix/*`.
- **`strict: true`** — la rama del PR tiene que estar actualizada con `main` antes de mergear.
- **`dismiss_stale_reviews: true`** — un push nuevo invalida las aprobaciones anteriores; si no,
  se aprueba una versión y se mergea otra.
- **`allow_force_pushes: false`** y **`allow_deletions: false`** — nadie reescribe ni borra `main`.
- **`enforce_admins: false`** — deja a la admin saltear la regla en una emergencia. Ponerlo en
  `true` es más estricto, pero si el CI se rompe no hay forma de mergear un arreglo.

> Si el comando falla pidiendo un upgrade de plan: el repo es **privado**, y la branch protection
> clásica no está disponible en todos los planes para repos privados. En ese caso la alternativa
> son los *Rulesets* (Settings → Rules → Rulesets), que expresan lo mismo.

Alternativa complementaria: un `.github/CODEOWNERS` hace que GitHub pida review automáticamente a
quien corresponda. No se agregó porque con 8 colaboradores y sin un mapa de propiedad claro, un
`*` termina embudando todos los PR en una sola persona. Vale la pena si más adelante se reparten
áreas.

## Orden de despliegue

El orden importa solo por las URLs internas: un servicio que arranca antes que su dependiente
simplemente falla los primeros health checks y Railway lo reintenta.

1. Base en Supabase: `init-multitenant.sql`, después `seed-demo.sql`.
2. `clip-embedding` — es el más lento en buildear (se bakean los pesos de CLIP en la imagen).
3. `rules-service`, `auth-service` — no dependen de nadie.
4. `classification-service` — necesita `rules-service` y `clip-embedding`.
5. `cases-service` — necesita `classification-service` y `rules-service`.
6. `arbiter-frontend` — necesita a los cuatro. Es el que recibe el dominio público.

---

## Ya hecho en el repo

- Los 5 backends escuchan en `${PORT:<puerto de siempre>}`, así que Railway les puede asignar el
  suyo sin romper docker-compose ni `mvn spring-boot:run`.
- `/actuator/health` expuesto y permitido sin JWT en los 5 (solo `health`, nada más del actuator).
  Es el `healthcheckPath` de cada `railway.json`.
- Pool de conexiones acotado a 5 por servicio (`DB_POOL_SIZE`), en vez de los 10 por defecto de
  Hikari.
- Todas las imágenes corren con usuario sin privilegios.
- Timezone fijada a `America/Argentina/Buenos_Aires` en las imágenes Java. **No es cosmético**: los
  contenedores corren en UTC, las máquinas del equipo en UTC-3, y hay `ZoneId.systemDefault()` en
  el cálculo de plazos legales (Ley 17.418 art. 56, las 72h de denuncia policial). Sin esto, una
  denuncia cargada 22:00 hora Argentina cae al día siguiente para el motor de reglas.
- `arbiter-frontend` tiene Dockerfile e imagen con Nginx, con `client_max_body_size 30m` para que
  los adjuntos (10MB por archivo / 30MB por request en `cases-service`) no reboten con un 413.
- El Nginx del frontend resuelve los backends **en tiempo de request**, no al arrancar. No es un
  detalle: con un `proxy_pass` a un hostname literal, nginx resuelve una sola vez al iniciar y
  **se niega a levantar** si el nombre no resuelve — o sea que el frontend entraba en crash-loop
  si arrancaba antes que los backends, o mientras alguno estuviera caído. Verificado: con los
  cuatro backends ausentes, la SPA sirve 200 y solo las rutas `/api/v1/*` dan 502.
- `railway.json` para los 6 servicios desplegables.

## Pendiente

- **Adjuntos en S3.** Hoy van como `bytea` en Postgres (`CaseServiceImpl.storeDocuments`), contra
  lo que dice la decisión #15. Se decidió dejarlo así para el primer despliegue; con el volumen de
  una demo aguanta, pero la base crece sin política de retención.
- **CI.** No hay pipeline. Railway despliega solo con `git push`, así que lo que falta es el gate
  de calidad (`mvn verify` + `ng build` por PR), no el deploy.
- **Versionado de migraciones.** Los `db/migrate-*.sql` no tienen tabla de control; hoy la única
  fuente de qué se aplicó es la memoria del equipo.
- **Logging estructurado / monitoreo.** Nada configurado.
- **Volver a Ollama** cuando haya dónde correrlo (ver arriba).
