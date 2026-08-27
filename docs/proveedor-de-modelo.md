# Proveedor del modelo — Ollama o Gemini por Vertex

El módulo de clasificación puede correr contra **dos proveedores** de modelo, y se elige con un
comando. Este documento es para el resto del equipo: cómo levantar con cada uno, qué setup hace
falta una sola vez, y cómo verificar que quedó andando.

> **Por qué existe esto.** Clasificar una denuncia por CPU tardaba entre 20 minutos y una hora, y
> eso vuelve el flujo imposible de probar de punta a punta. Por Gemini son segundos. El precio es
> que el prompt sale de nuestra infraestructura, así que **no es el default** y se activa a mano en
> cada máquina. La decisión #1 de arquitectura (modelo propio con Ollama) sigue siendo la del
> documento; esto es una herramienta de desarrollo mientras probamos con datos ficticios.

---

## 1 · Arranque rápido

Los dos scripts levantan el stack **contra la BD de Railway** (`docker-compose.railway.yml`): no
arrancan Postgres local, los módulos apuntan a la base compartida con el `DB_URL`/`DB_USER`/
`DB_PASSWORD` de tu `.env`. Es el mismo stack de siempre, con el proveedor de modelo ya elegido.

**No hace falta tocar el `.env`.** Cada script fija su proveedor y su perfil de compose, y pisa lo
que diga ese archivo.

**Ollama local** (el default, como veníamos):

```powershell
.\scripts\dev-ollama.ps1
```

**Gemini por Vertex** (no baja el modelo local — se ahorran ~11 GB de RAM):

```powershell
.\scripts\dev-gemini.ps1
```

La primera vez, Gemini necesita el setup de la sección 2. Sin él, el script corta con un mensaje
que dice qué falta, en vez de levantar contenedores rotos.

Los dos aceptan flags de `docker compose` al final (`-d` los deja corriendo en background):

```powershell
.\scripts\dev-gemini.ps1 --build -d
```

Son intercambiables: bajás con el comando de abajo y levantás con el otro. Para verificar cuál
quedó activo, mirá la sección 3.

```powershell
docker compose -f docker-compose.railway.yml down
```

> **Prerequisito:** la base de Railway ya tiene que tener el esquema y el seed cargados
> (`scripts/db-railway.ps1 all`). Con `ddl-auto=validate`, si falta algo el contenedor no arranca —
> mirá sus logs, Hibernate dice tabla/columna/tipo. `scripts/db-railway.ps1 check` confirma que el
> esquema está al día sin tocar nada.

> Si PowerShell bloquea los `.ps1` con un error de *execution policy*, una vez por máquina:
> `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.

---

## 2 · Setup de Gemini (una sola vez por máquina)

### 2.1 · Acceso al proyecto — por IAM, no mandando archivos

Hay dos formas de darte acceso al proyecto de GCP, y una es bastante peor.

**❌ Que te pasen el JSON de una service account.** Ese archivo **es** la credencial: quien lo tenga
gasta el crédito del proyecto, no queda registro de quién hizo qué, y revocarlo obliga a rotar la
clave para todos. Además termina circulando por chat.

**✅ Permiso en IAM, con tu propia cuenta.** El dueño del proyecto te agrega desde
**IAM y administración → Otorgar acceso** con **dos** roles:

| Rol | Para qué |
|-----|----------|
| **Usuario de Vertex AI** (`roles/aiplatform.user`) | llamar al modelo |
| **Consumidor de Service Usage** (`roles/serviceusage.serviceUsageConsumer`) | poder fijar el proyecto de cuota |

**Los dos, no solo el primero.** `aiplatform.user` no trae `serviceusage.services.use`, y sin ese
permiso el paso de abajo falla con este warning, que es fácil pasar por alto porque el login sí se
completa:

```
Cannot find a quota project to add to ADC. You might receive a "quota exceeded"
or "API not enabled" error.
```

### 2.2 · Tu credencial local

Con `gcloud` instalado (en Windows: `winget install --id Google.CloudSDK --exact`, y abrí una
terminal nueva después para que tome el PATH):

```bash
gcloud auth login
```

```bash
gcloud config set project project-fb32e998-d84b-4d72-911
```

```bash
gcloud auth application-default login
```

```bash
gcloud auth application-default set-quota-project project-fb32e998-d84b-4d72-911
```

**Los dos `login` son distintos y hacen falta los dos.** `gcloud auth login` autentica el *CLI*
—es el que usa el `set-quota-project` del final—, y `application-default login` genera la credencial
que consumen las *librerías*, o sea la que va al contenedor. Si salteás el primero, el último
comando falla con `This command is authenticated as None`.

Eso genera **tu** credencial de ADC, con tu cuenta. No viaja ningún secreto, cada llamada queda
registrada a tu nombre, y se revoca sacándote de IAM. `dev-gemini.ps1` la encuentra sola y la monta
de solo lectura en el contenedor — no hay que escribir ningún `docker-compose.override.yml` para
esto.

**El proyecto de cuota no es opcional** aunque parezca redundante: sin él, Vertex rechaza las
llamadas con un error de permisos que no dice que el problema es ese. Por eso van los tres comandos
y en ese orden.

Si falla con `PERMISSION_DENIED`, te falta el rol **Consumidor de Service Usage** de la tabla de
arriba — pedíselo al dueño del proyecto y volvé a correrlo. No hace falta rehacer el login.

### 2.3 · Si tu cuenta es institucional y te dice `USER_BLOCKED_BY_ADMIN`

```
Authentication error: 7; Error Details: User not allowed to access GCP services.
reason: USER_BLOCKED_BY_ADMIN
```

Esto **no** se arregla con roles: los roles dicen qué podés hacer *dentro* del proyecto, y este
error es anterior — el administrador del Workspace de **tu** organización (`@frba.utn.edu.ar`, o el
dominio de tu trabajo) tiene deshabilitado el servicio de Google Cloud para sus cuentas. Es común en
cuentas de facultad.

La salida es usar una **cuenta personal de Gmail**: pedile al dueño del proyecto que te agregue con
esa, y rehacé los cuatro comandos de arriba eligiéndola en el navegador. Verificá con qué cuenta
quedaste:

```bash
gcloud auth list
```

### 2.4 · Alternativa: tu propio proyecto

Si preferís no consumir el crédito de otro, abrí tu propio proyecto de GCP: son **USD 300 por
cuenta, válidos 90 días desde el alta**, y cada expediente cuesta centavos. Habilitá la API con

```bash
gcloud services enable aiplatform.googleapis.com
```

y exportá tu project id como `GOOGLE_CLOUD_PROJECT` antes de correr el script (o ponelo en tu
`.env`) — el default está en `docker-compose.gemini.yml`. Cero coordinación con el resto.

---

## 3 · Cómo verificar que anda

Al arrancar, `classification-service` loguea qué proveedor quedó activo:

```powershell
docker logs arbiter-railway-classification-service-1 | Select-String "Gemini|Ollama|Started"
```

> El contenedor se llama `arbiter-railway-*` y no `arbiter-*`: `docker-compose.railway.yml` fija
> `name: arbiter-railway` a propósito, para que este stack y el local no se pisen por nombre.

Con Gemini tiene que aparecer:

```
[Gemini] Vertex backend — project=project-fb32e998-... location=global model=gemini-3.5-flash
```

Para probar el flujo de verdad, cargá una denuncia **que no sea Fast Track**. Esto importa: Fast
Track lo decide `FastTrackValidator` con reglas de negocio y **el modelo no clasifica** — vas a ver
que resuelve rápido y no vas a haber probado nada del clasificador.

La forma más simple de romper el gate es el monto: la póliza `POL-CEL-2026-042` tiene suma asegurada
$1.300.000, así que el umbral está en $650.000. Con los fixtures de
`docs/postman/test-docs/conMarcaDePrueba/celulares/robo/` pero reclamando **$900.000**, el gate no
pasa y va al modelo.

En los logs vas a ver una llamada por documento más la del clasificador:

```
[Gemini] generateContent — model=gemini-3.5-flash images=1
[Gemini] Tokens — prompt=2461 candidates=816 total=4347
[Gemini] Response received in 11249 ms
```

Y en la base, una fila en `llm_analysis` con el modelo que respondió, la recomendación, la confianza
y los factores.

> **Ojo con los fixtures `conMarcaDePrueba/`**: llevan al pie la leyenda "documento simulado", y el
> modelo la lee y la usa como factor para no recomendar la aprobación. Es correcto de su parte, pero
> te contamina la prueba. Para medir cómo clasifica de verdad, usá `sinMarca/` (firmados por Roman
> Castillo, sin leyenda).

---

## 4 · Costos y cuidados

- Se paga **por token**, no por tiempo encendido: que prueben cinco personas o una cuesta casi lo
  mismo. Un expediente son centavos de dólar.
- Los **USD 300 vencen a los 90 días** del alta de la cuenta, no del primer uso.
- Poné una **alerta de presupuesto** (Facturación → Presupuestos y alertas) el primer día. Si la
  cuenta pasó a paga, cuando se agote el crédito empieza a cobrar la tarjeta.
- El sidecar de embeddings (CLIP) **no cambia**: los vectores de imágenes nunca pasaron por el LLM y
  siguen calculándose local.
- `global` y no una región concreta: los modelos Gemini 3 **solo** se sirven desde el endpoint
  global y desde las multirregión `us`/`eu`. Con `us-central1` da 404. La contra es que con `global`
  no se controla dónde ocurre el procesamiento — irrelevante mientras probemos con datos sintéticos,
  relevante el día que haya datos reales.

---

## 5 · Levantar sin los scripts

Los scripts son una comodidad, no un requisito. Equivalen a:

```bash
docker compose -f docker-compose.railway.yml -f docker-compose.ollama.yml up --build -d
```

```bash
docker compose -f docker-compose.railway.yml -f docker-compose.gemini.yml up --build -d
```

con `COMPOSE_PROFILES` puesto (`ollama` para el primero, cualquier otra cosa para el segundo) y,
para Gemini, `GOOGLE_ADC_HOST_PATH` apuntando a tu credencial.

A mano, el proveedor sale de `LLM_PROVIDER` (`arbiter.llm.provider`), y el contenedor de Ollama está
detrás de un **perfil de compose** para que quien use Gemini no se baje 6 GB de pesos ni les reserve
la RAM (se midieron **11,16 GB** en uso por ese contenedor).

Eso implica que un `docker compose up -d` pelado levanta todo **menos Ollama**, y la clasificación
falla recién al primer siniestro con un `Connection refused` contra `ollama:11434` — el stack
arranca sin error, así que la causa no es obvia. El arreglo es una línea en tu `.env`:

```
COMPOSE_PROFILES=ollama
```

Aplica a los **dos** archivos, `docker-compose.yml` y `docker-compose.railway.yml`: se mantienen
alineados a propósito (ya se desalinearon una vez con `RULES_SERVICE_URL` y costó una tarde).

Para Gemini a mano hay que poner `LLM_PROVIDER=gemini` y `GOOGLE_CLOUD_PROJECT` en el `.env`,
comentar `COMPOSE_PROFILES`, y montar la credencial en tu `docker-compose.override.yml`:

```yaml
services:
  classification-service:
    environment:
      GOOGLE_APPLICATION_CREDENTIALS: /secrets/adc.json
    volumes:
      - <TU_RUTA>/gcloud/application_default_credentials.json:/secrets/adc.json:ro
```

En Windows la ruta suele ser `C:/Users/<vos>/AppData/Roaming/gcloud/...`; en Linux/Mac,
`~/.config/gcloud/...`. Es exactamente lo que `dev-gemini.ps1` resuelve solo.

El código de Ollama quedó intacto detrás de la misma interfaz `LlmClient`: las clases de Gemini
están detrás de `@ConditionalOnProperty`, así que con Ollama ni se instancian y no necesitás cuenta
de Google para que el servicio levante.
