# Proveedor del modelo — Ollama o Gemini por Vertex

Desde la rama `feature/fraude-derivacion-perito`, el módulo de clasificación puede correr contra
**dos proveedores** de modelo, y se elige por entorno. Este documento es para el resto del equipo:
qué cambia si no hacés nada, cómo seguir con Ollama, y cómo probar Gemini.

> **Por qué existe esto.** Clasificar una denuncia por CPU tardaba entre 20 minutos y una hora, y
> eso vuelve el flujo imposible de probar de punta a punta. Por Gemini son segundos. El precio es
> que el prompt sale de nuestra infraestructura, así que **no es el default** y se activa a mano en
> cada máquina. La decisión #1 de arquitectura (modelo propio con Ollama) sigue siendo la del
> documento; esto es una herramienta de desarrollo mientras probamos con datos ficticios.

---

## 1 · Lo único que se rompe si no hacés nada

El contenedor de Ollama quedó detrás de un **perfil de compose**, para que quien use Gemini no se
baje 6 GB de pesos ni les reserve la RAM (se midieron **11,16 GB** en uso por ese contenedor).

Eso significa que `docker compose up -d` levanta todo **menos Ollama**, y la clasificación falla
recién al primer siniestro con un `Connection refused` contra `ollama:11434` — el stack arranca sin
error, así que la causa no es obvia.

**El arreglo es una línea en tu `.env`:**

```
COMPOSE_PROFILES=ollama
```

Como el `.env` está gitignoreado, nadie la recibe automáticamente. Está documentada en
`.env.example`.

**Nada más cambia para vos:** el proveedor por defecto sigue siendo Ollama, y las clases de Gemini
están detrás de `@ConditionalOnProperty`, así que ni se instancian. No necesitás cuenta de Google
ni credenciales para que el servicio levante.

---

## 2 · Probar Gemini

### 2.1 · Configuración (5 minutos)

**a. En tu `.env`:**

```
LLM_PROVIDER=gemini
GOOGLE_CLOUD_PROJECT=<el project id>
GOOGLE_CLOUD_LOCATION=global
GEMINI_MODEL=gemini-3.5-flash
```

Y **comentá `COMPOSE_PROFILES=ollama`** — con Gemini no hace falta el modelo local, y ahí es donde
se ganan los 11 GB.

> `global` y no una región concreta: los modelos Gemini 3 **solo** se sirven desde el endpoint
> global y desde las multirregión `us`/`eu`. Con `us-central1` te da 404. La contra es que con
> `global` no se controla dónde ocurre el procesamiento — irrelevante mientras probemos con datos
> sintéticos, relevante el día que haya datos reales.

**b. En tu `docker-compose.override.yml`** (gitignoreado, cada uno el suyo), montá tu credencial:

```yaml
services:
  classification-service:
    environment:
      GOOGLE_APPLICATION_CREDENTIALS: /secrets/adc.json
    volumes:
      - <TU_RUTA>/gcloud/application_default_credentials.json:/secrets/adc.json:ro
```

En Windows la ruta suele ser `C:/Users/<vos>/AppData/Roaming/gcloud/...`; en Linux/Mac,
`~/.config/gcloud/...`. Hay un ejemplo comentado en `docker-compose.yml`, en el bloque de
`classification-service`.

**c. Levantar:**

```bash
docker compose up -d --build classification-service
```

Si en los logs aparece `[Gemini] Vertex backend — project=... location=global model=...`, quedó.

### 2.2 · Credenciales — hacelo por IAM, no mandando archivos

Hay dos formas de darte acceso al proyecto de GCP, y una es bastante peor.

**❌ Que te pasen el JSON de una service account.** Ese archivo **es** la credencial: quien lo tenga
gasta el crédito del proyecto, no queda registro de quién hizo qué, y revocarlo obliga a rotar la
clave para todos. Además termina circulando por chat.

**✅ Permiso en IAM, con tu propia cuenta.** El dueño del proyecto te agrega desde
**IAM y administración → Otorgar acceso** con el rol **Usuario de Vertex AI**
(`roles/aiplatform.user`). Después, en tu máquina:

```bash
gcloud auth application-default login
```

```bash
gcloud auth application-default set-quota-project <el-project-id>
```

Eso genera **tu** credencial de ADC, con tu cuenta. No viaja ningún secreto, cada llamada queda
registrada a tu nombre, y se revoca sacándote de IAM.

> Si no tenés `gcloud`, en Windows se instala con `winget install --id Google.CloudSDK --exact`.
> Abrí una terminal nueva después de instalarlo para que tome el PATH.

**El `set-quota-project` no es opcional** aunque parezca redundante: sin proyecto de cuota asignado,
Vertex rechaza las llamadas con un error de permisos que no dice que el problema es ese.

### 2.3 · Alternativa: tu propio proyecto

Si preferís no consumir el crédito de otro, abrí tu propio proyecto de GCP: son **USD 300 por
cuenta, válidos 90 días desde el alta**, y cada expediente cuesta centavos. Habilitá la API con

```bash
gcloud services enable aiplatform.googleapis.com
```

y poné tu project id en el `.env`. Cero coordinación con el resto.

---

## 3 · Cómo verificar que anda

Cargá una denuncia **que no sea Fast Track**. Esto importa: Fast Track lo decide `FastTrackValidator`
con reglas de negocio y **el modelo no clasifica** — vas a ver que resuelve rápido y no vas a haber
probado nada del clasificador.

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

## 5 · Volver a Ollama

Sacá `LLM_PROVIDER` del `.env` (o ponelo en `ollama`), descomentá `COMPOSE_PROFILES=ollama` y
levantá de nuevo. El código de Ollama quedó intacto detrás de la misma interfaz `LlmClient`.
