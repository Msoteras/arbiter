# Despliegue en Railway

Estado: **preparado, sin desplegar todavía.** Este documento es el procedimiento; lo que ya está
hecho del lado del repo se marca como tal.

La BD **no** va en Railway: va en **Supabase** (ver "Base de datos" abajo).

---

## Topología

Seis servicios de Railway, todos desde el mismo repo. **Solo uno tiene dominio público.**

| Servicio Railway | Root Directory | `RAILWAY_DOCKERFILE_PATH` | Público | Notas |
|---|---|---|---|---|
| `arbiter-frontend` | `/` | `arbiter-frontend/Dockerfile` | **Sí** | Sirve la SPA y hace de reverse proxy de `/api/v1/*` |
| `auth-service` | `/` | `auth-service/Dockerfile` | No | |
| `rules-service` | `/` | `rules-service/Dockerfile` | No | |
| `classification-service` | `/` | `classification-service/Dockerfile` | No | |
| `cases-service` | `/` | `cases-service/Dockerfile` | No | |
| `clip-embedding` | `/` | `embedding-service/Dockerfile` | No | Contexto en la raíz como los demás (ver abajo) |

**El nombre del servicio en Railway tiene que ser exactamente el de esta tabla**: los hostnames
`*.railway.internal` se derivan de él y el Nginx del frontend los tiene cableados.

`reports-service` **no se despliega**: hoy no tiene ni un controller. Tiene Dockerfile y
`application.yml` al día, así que sumarlo después es crear el servicio y nada más.

### Por qué no hay `railway.json`: Config as Code quedó deprecado

**Railway deprecó Config as Code y el corte nos dejó afuera por 48 horas.** Desde el **2026-08-28**
los servicios que nunca usaron Config as Code **no pueden habilitarlo**, y los nuestros nunca lo
usaron. Los seis `railway.json` que había en el repo no se iban a leer nunca, así que se borraron
el 30/08 en vez de dejarlos como trampa para el próximo que los lea y asuma que la configuración
está versionada. El reemplazo oficial es
[Infrastructure as Code](https://docs.railway.com/infrastructure-as-code) (`.railway/railway.ts`).

**No migramos a IaC**: es TypeScript + `npm install railway` + `railway config plan/apply` corrido
**a mano** (no se dispara en el push). Meter una dependencia npm y un archivo TS en un monorepo Java
para una config que se carga una vez no se justifica. Todo lo que decían los `railway.json` tiene
equivalente vigente:

| Lo que decía el `railway.json` | Dónde va ahora |
|---|---|
| `build.dockerfilePath` | Variable de servicio `RAILWAY_DOCKERFILE_PATH` — **no está deprecada** |
| `build.builder: DOCKERFILE` | Se activa solo al encontrar el Dockerfile; si no, Settings → Build → Builder |
| `deploy.healthcheckPath` / `healthcheckTimeout` | Settings → Deploy → Healthcheck |
| `deploy.restartPolicyType` / `MaxRetries` | Settings → Deploy → Restart Policy (`ON_FAILURE`, 10) |

`RAILWAY_DOCKERFILE_PATH` **se resuelve desde la raíz del repositorio, no desde el Root
Directory** (igual que el viejo Config File Path). Los seis servicios llevan Root Directory `/` y
la ruta completa desde la raíz — sin excepciones, ver la sección de `clip-embedding` más abajo.

Si no se carga `RAILWAY_DOCKERFILE_PATH`, Railway no encuentra ningún Dockerfile en la raíz,
**cae a autodetección** con su builder de Java (Railpack) y el deploy muere. No falla al construir
— falla al arrancar, que es peor, porque el build sale en verde.

Cómo se reconoce que pasó eso:

| Señal | Por qué |
|---|---|
| El build compila **los 5 backends** en vez de uno | Railpack vio el POM multi-módulo de la raíz y buildeó el reactor entero. Los Dockerfiles hacen lo contrario: un `sed` borra los otros módulos del POM padre y corren `mvn -pl <modulo> -am` |
| `The requested profile "production" could not be activated because it does not exist` | Es un perfil de **Maven**, no de Spring. Lo inyecta Railpack (`mvn ... -Pproduction`). No hay ningún perfil de Spring que crear: la config del proyecto es toda por variables de entorno |
| `Error: Unable to access jarfile target/*jar` en loop | Es el start command por defecto de Railpack, corriendo contra la raíz del repo — donde el POM padre es `packaging: pom` y nunca va a haber un jar. El ENTRYPOINT propio es `java -Duser.timezone=... -jar app.jar` desde `/app` |

Valores por servicio (restart policy `ON_FAILURE` / 10 en los seis):

| Servicio | `RAILWAY_DOCKERFILE_PATH` | Healthcheck | Timeout |
|---|---|---|---|
| `arbiter-frontend` | `arbiter-frontend/Dockerfile` | `/` | 120 |
| `auth-service` | `auth-service/Dockerfile` | `/actuator/health` | 300 |
| `rules-service` | `rules-service/Dockerfile` | `/actuator/health` | 300 |
| `classification-service` | `classification-service/Dockerfile` | `/actuator/health` | 600 |
| `cases-service` | `cases-service/Dockerfile` | `/actuator/health` | 300 |
| `clip-embedding` | `embedding-service/Dockerfile` | `/health` | 600 |

### Watch Paths: que un push no rebuildee los seis

Los seis servicios apuntan al mismo repo, así que **por defecto cualquier push a `main` dispara
seis builds** — cuatro de ellos compilaciones de Maven completas. Settings → Build → *Watch Paths*
(reglas estilo `.gitignore`) acota cada servicio a lo que realmente lo afecta:

| Servicio | Watch Paths |
|---|---|
| `auth-service` | `/auth-service/**`, `/common-lib/**`, `/pom.xml` |
| `rules-service` | `/rules-service/**`, `/common-lib/**`, `/pom.xml` |
| `classification-service` | `/classification-service/**`, `/common-lib/**`, `/pom.xml` |
| `cases-service` | `/cases-service/**`, `/common-lib/**`, `/pom.xml` |
| `arbiter-frontend` | `/arbiter-frontend/**` |
| `clip-embedding` | `/embedding-service/**` |

`common-lib` y el POM padre van en los cuatro backends porque un cambio ahí **sí** los afecta a
todos: es la dependencia real que declaran sus Dockerfiles, no una precaución. Sacarlos es el error
que produce el peor síntoma posible — un módulo desplegado contra una versión vieja de `common-lib`,
sin ningún build fallado que lo delate.

El frontend y `clip-embedding` no los necesitan: no dependen de Maven.

### Los seis builds tienen el contexto en la raíz, `clip-embedding` incluido

**`RAILWAY_DOCKERFILE_PATH` se resuelve desde la raíz del repositorio, no desde el Root
Directory.** Eso deja sin salida a cualquier servicio que quiera un contexto acotado a su carpeta:
el Root Directory define el **contexto**, la variable define **dónde está el Dockerfile**, y los
dos no pueden apuntar a lugares distintos. Con Root Directory `embedding-service`, la variable en
`Dockerfile` falla (`couldn't locate the dockerfile at path Dockerfile`) y en
`embedding-service/Dockerfile` tampoco resuelve de forma confiable.

Por eso `embedding-service/Dockerfile` **copia con rutas desde la raíz** (`COPY
embedding-service/app.py .`), igual que los cinco restantes, y los dos `docker-compose` lo
construyen con `context: .` + `dockerfile: embedding-service/Dockerfile`. No necesita nada de
afuera de su carpeta —a diferencia de los backends Java, que sí precisan el POM padre y
`common-lib`—, pero comparte la regla para que no haya excepciones que recordar:

> **Root Directory `/` y `RAILWAY_DOCKERFILE_PATH=<servicio>/Dockerfile` en los seis.**

Si alguien "simplifica" ese Dockerfile sacándole el prefijo `embedding-service/` a los `COPY`, el
build local con `context: .` va a fallar con `"/app.py": not found`.

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
| `GOOGLE_APPLICATION_CREDENTIALS_B64` | Solo `classification-service`. La service-account key de Vertex en base64 — ver "La credencial de Vertex en Railway" abajo. **No** `GOOGLE_APPLICATION_CREDENTIALS`, que espera una ruta a un archivo que en Railway no existe |
| `SPRING_PROFILES_ACTIVE` | **`insurer-db`** en `auth-service`, `classification-service` y `cases-service`. No es un perfil de entorno: activa los adapters que leen la BD Aseguradora (`InsurerDatabaseAdapter`, `InsuredDirectoryDatabaseAdapter`), que son `@Primary` sobre los mocks. **Sin él no falla nada**: los tres arrancan, pasan el healthcheck y sirven pólizas y asegurados **inventados** por `MockInsurerAdapter` / `MockInsuredDirectoryAdapter`. El único rastro es un `log.warn` al arrancar |
| `JWT_SECRET` | **El mismo valor en los 5 servicios.** Si difieren, los tokens de servicio entre módulos se rechazan y el síntoma es un 401 sin explicación |
| `PASSWORD_ENCRYPTION_PRIVATE_KEY` | Ver abajo |
| `PORT` | **Setearla a mano** en cada backend: `auth-service` 8080, `rules-service` 8081, `classification-service` 8082, `cases-service` 8083. Ver abajo |
| `JAVA_TOOL_OPTIONS` | **`-XX:MaxRAMPercentage=75.0` en los 4 backends.** No está en los Dockerfiles (solo en `docker-compose.railway.yml`), así que en Railway hay que cargarla a mano. Sin ella la JVM toma como heap máximo el **25%** de la memoria del contenedor y Spring Boot con JPA se queda corto: GC constante y OOM bajo carga |

El frontend además necesita las cuatro `*_SERVICE_URL` que consume su Nginx (ya vienen con default
en su Dockerfile, apuntando a los nombres `.railway.internal` de la tabla de arriba).

### Por qué `PORT` va fijada a mano

Railway inyecta un `PORT` propio si no la definís, y los backends escuchan en `${PORT:<puerto de
siempre>}`, así que obedecen. **Pero el frontend tiene los upstreams cableados a puertos fijos**
(`:8080`, `:8081`, `:8082`, `:8083`, en `nginx.conf.template` vía los defaults del Dockerfile). Si
Railway asigna otro, nginx sigue buscando en el de siempre y no encuentra nada.

El modo de fallo es especialmente feo: si Railway asigna 8080 por defecto, **`auth-service` anda y
los otros tres no**. Un 502 parcial en tres de cuatro backends parece un problema de esos servicios,
no de configuración de red.

Fijando `PORT` por servicio, la red privada queda alineada con la tabla de puertos del `CLAUDE.md`,
con `docker-compose` y con lo que ya espera el frontend. La alternativa —referenciar el puerto ajeno
desde el frontend con `${{auth-service.PORT}}`— son cuatro variables más para el mismo resultado.

**Los nombres de los servicios en Railway tienen que ser exactos**, porque los hostnames
`*.railway.internal` se derivan de ellos: `auth-service`, `rules-service`, `classification-service`,
`cases-service`, `clip-embedding`. Un servicio llamado `auth` en vez de `auth-service` da 502 en
todas sus rutas sin ningún mensaje que explique por qué.

### `LLM_PROVIDER=gemini` es una desviación consciente

La **decisión de arquitectura #1** dice LLM en infraestructura propia con Ollama, sin APIs externas.
Ollama no entra en Railway: el propio `docker-compose.railway.yml` le reserva `mem_limit: 14g` y
aclara que corre al lado, no en Railway.

Se acordó arrancar con Gemini para las primeras pruebas y volver a Ollama después. **Es deuda
explícita, no un cambio de decisión.** Para volver a Ollama hay que levantarlo en una máquina con
GPU fuera de Railway y apuntarle `OLLAMA_BASE_URL` — el código ya soporta las dos vías y se cambia
con una variable.

#### La credencial de Vertex en Railway

`GeminiConfig` construye el cliente con `.vertexAI(true)` y autentica por **ADC**, que solo lee la
credencial de un **archivo** apuntado por `GOOGLE_APPLICATION_CREDENTIALS`. En Compose eso se
resuelve montando el archivo (`docker-compose.railway.vertex.yml`); en Railway no hay dónde montar
nada.

**No se cambió a API key**, aunque sería más simple: llegaría a la Gemini Developer API en vez de
Vertex, cuyo tier gratuito puede usar los prompts para mejorar los productos de Google. Con datos
de siniestros de personas reales eso sería una segunda desviación de privacidad encima de la que ya
representa usar Gemini.

La solución es `classification-service/docker-entrypoint.sh`: si existe
`GOOGLE_APPLICATION_CREDENTIALS_B64`, escribe la credencial a `/tmp/gcp-adc.json`, exporta
`GOOGLE_APPLICATION_CREDENTIALS` y recién ahí lanza la JVM. Si la variable no está, no hace nada —
por eso Compose sigue funcionando igual, con su archivo montado.

**Va en base64 y no como JSON crudo** porque una service-account key es multilínea y el Raw Editor
de Railway parsea `CLAVE=valor` línea por línea: pegando el JSON directo se trunca en el primer
salto de línea.

Para generar el valor, desde donde tengas el JSON de la service account:

```bash
base64 -w0 service-account.json
```

En PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("service-account.json"))
```

Hace falta **una service account creada en el proyecto de GCP con permiso sobre Vertex AI**, no la
credencial personal de `gcloud auth application-default login` que usa el override de Compose: esa
está atada al usuario y a su máquina.

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
  Es el healthcheck que se carga en Settings → Deploy de cada servicio.
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
- **No hay `railway.json`**: se borraron (30/08) al quedar Config as Code deprecado sin que
  pudiéramos habilitarlo. Toda la configuración de despliegue vive en el dashboard de Railway y
  está documentada acá — este archivo es la única fuente de verdad.

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
