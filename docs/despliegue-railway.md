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

Ya está configurado, con **rulesets** (Settings → Rules → Rulesets), no con la branch protection
clásica. Hay dos, `Protección main` y `develop`, y ambos aplican lo mismo:

| Regla | Efecto |
|---|---|
| `pull_request` con 1 aprobación | Nadie mergea solo. GitHub no deja aprobar el propio PR, así que el que aprueba es siempre otra persona |
| `require_last_push_approval` | Un push después de aprobar invalida la aprobación: no se aprueba una versión y se mergea otra |
| `dismiss_stale_reviews_on_push` | Ídem, descarta las reviews viejas |
| `non_fast_forward` | Nadie reescribe la historia de esas ramas |
| `deletion` | Nadie las borra |

Consecuencia práctica: **ni `main` ni `develop` aceptan push directo.** Todo entra por PR, incluido
lo que antes se pusheaba a `develop` sin más.

Ojo que esto **no es un hook de git**: los hooks son locales, no ven PRs ni aprobaciones y se
saltean con `--no-verify`. Los rulesets corren del lado de GitHub, y solo puede tocarlos quien
tenga rol de admin en el repo (hoy, `Msoteras`).

### Los checks del CI no son obligatorios

Ninguno de los dos rulesets tiene `required_status_checks`. O sea que el CI y el guard de
`guard-main.yml` **informan pero no bloquean**: si están en rojo, el PR se puede mergear igual.

Es una decisión tomada, no un olvido. Si en algún momento se quiere que además bloqueen, hay que
agregar estos cuatro contexts al ruleset de `main` (los nombres tienen que coincidir exactos con
el `name:` de cada job, o GitHub espera para siempre un check que nunca llega):

- `Backend (Java 21)`
- `Frontend (Angular 20)`
- `Ruteo dev == ruteo prod`
- `PR a main solo desde develop`

### De dónde puede venir un PR a `main`

`.github/workflows/guard-main.yml` acepta solo `develop` y `hotfix/*`, y rechaza cualquier otra
rama: un PR de una feature directo a `main` se saltea la integración en `develop` y despliega
código que nunca convivió con el resto.

**Un hotfix mergeado a `main` no vuelve solo a `develop`.** Hay que mergearlo también ahí, o el
próximo despliegue de `develop` lo pisa. El workflow lo avisa con un warning, pero avisar es todo
lo que puede hacer.

Alternativa complementaria, no agregada: un `.github/CODEOWNERS` hace que GitHub pida review
automáticamente a quien corresponda. Con 8 colaboradores y sin un mapa de propiedad claro, un `*`
termina embudando todos los PR en una sola persona. Vale la pena si más adelante se reparten áreas.

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
