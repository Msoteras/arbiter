# Anexo — Motor de análisis: conceptos de negocio, multi-tenant e invocación a Gemini

**ARBITER — Sistema de Gestión del Ciclo de Vida de Siniestros** · K5054 — Grupo 5303

## 1. Propósito y alcance

Este anexo documenta cómo el motor de análisis de ARBITER traduce las reglas de negocio de cada aseguradora en una invocación a Gemini, y cómo se garantiza que la información de una compañía nunca se mezcle con la de otra.

Cubre los conceptos de negocio que condicionan el análisis, la implementación multi-tenant de la persistencia y del contexto de ejecución, y los parámetros de las tres integraciones externas del motor: Gemini, Google Cloud Vision API y la generación de embeddings.

Queda fuera del alcance la liquidación monetaria y el pago, que ARBITER conoce como estados de frontera pero no ejecuta. Los valores consignados se tomaron del repositorio del prototipo (rama `main`).

## 2. Conceptos de negocio

El motor de análisis no decide con criterios propios: aplica los criterios que cada aseguradora define. Por eso, antes de describir la implementación, se fijan los conceptos que la determinan.

| Concepto | Definición operativa en ARBITER | Impacto en la implementación |
| --- | --- | --- |
| Aseguradora (tenant) | Compañía dada de alta en la plataforma. Es la unidad de aislamiento de datos y de configuración. | Cada aseguradora tiene su propio esquema de base de datos. Sus reglas, ramos y configuración de puntaje viven en ese esquema. |
| Referente aseguradora | Usuario de la compañía que carga y modifica reglas, umbrales y flujos de resolución. | Las reglas se leen de rules-service en cada análisis; no se despliega código para cambiarlas. |
| Ramo | Categoría de producto asegurador. El MVP se acota a celulares y tecnología portátil. | Un ramo nuevo se incorpora por configuración, no por desarrollo. |
| Póliza | Contrato entre la aseguradora y el asegurado: vigencia, coberturas, cláusulas y estado de pago. | Es la fuente de los datos intrínsecos que evalúa la Capa 1. |
| Siniestro / expediente | Evento dañoso denunciado y el legajo que lo documenta, con su historial de estados. | Es la unidad de clasificación: cada recomendación del modelo corresponde a un expediente. |
| Capa 1 (Fast Track) | Validaciones deterministas resolubles con datos de la relación póliza–cliente: prescripción, exclusiones de cobertura, vigencia, carencia, plazos de denuncia, máximo de eventos por año, estado de pago, grupo familiar, suma asegurada agotada, antecedentes de fraude y los criterios del Fast Track. | Las resuelven reglas, sin pedir una clasificación al modelo. El modelo solo lee los documentos que exige el Fast Track. La prescripción y las exclusiones cierran la recomendación sin modelo; las demás reglas bloquean el Fast Track y pasan como contexto a la Capa 2. |
| Capa 2 | Casos no deterministas: requieren interpretar documentación, imagen y contexto. | Es la única capa que pide una clasificación al modelo (Gemini u Ollama). Produce una clasificación preliminar y señalamientos para el analista. |
| Tipología | Clasificación del siniestro por antigüedad de cobertura, siniestralidad previa y monto: exprés, documentación reducida, servicio técnico, urgente o documentación amplia. | El prototipo no la modela como campo. Sus tres variables entran como criterios del Fast Track: antigüedad de la póliza, siniestros previos y relación entre monto y suma asegurada. |
| Índice de riesgo de fraude | Valoración compuesta de indicadores de irregularidad. Los pesos de cada variable los define cada aseguradora, por ramo y hecho generador. | Lo calcula `RiskScoringService` como promedio ponderado, fuera del modelo, y se guarda en `risk_analysis`. No entra en el prompt ni decide la clasificación. |
| Analista de siniestros | Usuario que aprueba o rechaza toda clasificación antes de que impacte en el expediente. | Ninguna respuesta del modelo modifica el expediente sin su intervención (Disposición SSN 2/2023). |

Los umbrales de monto que separan las tipologías y las atribuciones por rol dependen del Anexo II de la aseguradora, que no está disponible. Se modelan como parámetros configurables y no se consignan cifras.

## 3. Implementación multi-tenant

ARBITER aísla a cada aseguradora en un esquema propio dentro de una única instancia de PostgreSQL. Los cinco servicios Java (auth-service, rules-service, classification-service, cases-service y reports-service) implementan el mismo mecanismo en su paquete `config/tenant`.

Se descartó la base compartida con columna discriminadora porque un filtro omitido en una consulta expondría datos de otra compañía. También se descartó una instancia por aseguradora, que multiplica el costo de operación sin aportar un aislamiento necesario en esta etapa.

### 3.1. Distribución de los datos

| Esquema | Contenido |
| --- | --- |
| `arbiter_common` | Catálogo de aseguradoras (`insurer`, cuyo `schema_name` es la clave de ruteo), usuarios, roles y permisos, pertenencia usuario–aseguradora (`user_insurer`), estados de expediente y tipos de documento. |
| `arbiter_<aseguradora>` (p. ej. `arbiter_bbva`, `arbiter_provincia`) | Perfiles (asegurados, analistas y referentes), ramos, hechos generadores y coberturas, copias de las pólizas, reglas y configuración de puntaje, agenda documental, expedientes, documentos adjuntos, análisis de documentos, análisis del modelo (`llm_analysis`), resultados de reglas (`rule_result`), análisis de riesgo e imágenes con sus embeddings. |
| `aseguradora_<aseguradora>` | Base propia de la compañía: pólizas, coberturas, asegurados e historial de siniestros. Su nombre se deriva del tenant (`arbiter_bbva` → `aseguradora_bbva`) en `InsurerDbSchema`, para que no pueda desalinearse con el catálogo. En el prototipo es un esquema de la misma instancia. |

### 3.2. Resolución del tenant en cada solicitud

1. Auth0 valida las credenciales del usuario y auth-service emite un JWT propio, firmado con la clave compartida `JWT_SECRET`. El token lleva los claims `rol`, `nombre`, `apellido`, `insurerIds` y `tenantSchema`; el del asegurado suma `insuredId` y `onboardingComplete`.
2. En cada servicio, `TenantResolvingFilter` verifica la firma, lee `tenantSchema` y lo guarda en `TenantContext`, un `ThreadLocal`. El esquema nunca se toma de un parámetro enviado por el cliente.
3. Hibernate obtiene ese valor mediante `TenantIdentifierResolver` (implementa `CurrentTenantIdentifierResolver`).
4. `TenantConnectionProvider` (implementa `MultiTenantConnectionProvider`) toma una conexión del pool único y ejecuta `SET search_path TO <tenant>, arbiter_common, public`. Antes valida el nombre contra `^[a-z_][a-z0-9_]*$`, porque un identificador no puede pasarse como parámetro de la consulta.
5. Al devolver la conexión, restablece el `search_path` a `arbiter_common`: HikariCP no limpia el estado de sesión y el tenant pasaría al siguiente uso de esa conexión.
6. Al terminar la solicitud, el filtro limpia `TenantContext`.

Si el token no trae `tenantSchema`, el contexto cae en `arbiter_common` y no en el último tenant que usó la conexión. Por la misma razón se desactiva `open-in-view`: una sesión abierta durante toda la solicitud quedaría fijada al tenant de la primera consulta. Los repositorios que usan JDBC directo califican sus tablas con `TenantContext.schemaForSql()`, porque esas conexiones no pasan por el proveedor de Hibernate.

### 3.3. Procesos asíncronos

La clasificación responde `202 Accepted` y continúa en otro hilo, cuando el `ThreadLocal` de la solicitud ya se limpió. `TenantAwareTaskDecorator` copia el esquema al hilo de la tarea y lo limpia en un `finally`, para que un hilo reutilizado nunca arrastre el esquema de otra aseguradora.

### 3.4. Alta de una aseguradora

Incorporar una compañía no requiere escribir DDL ni redesplegar:

1. Se inserta la aseguradora en `arbiter_common.insurer` con su `schema_name`.
2. `arbiter_common.create_tenant_schema('arbiter_<aseguradora>')` crea el esquema con todas sus tablas e índices.
3. `arbiter_common.create_insurer_db_schema(esquema, razón social, CUIT, registro SSN)` crea el esquema de la base propia de la compañía.
4. El referente aseguradora carga reglas, umbrales y pesos del índice de fraude desde la interfaz.

Los cambios de estructura posteriores se aplican con scripts SQL fechados en `db/migrations`.

## 4. Invocación a Gemini

Gemini es uno de los dos proveedores intercambiables del motor de análisis. Ambos implementan la interfaz `LlmClient`, y la propiedad `arbiter.llm.provider` (`LLM_PROVIDER`) elige cuál se usa. El valor por defecto es `ollama` (modelo local `qwen3-vl:8b-instruct`); `gemini` se activa de forma explícita, porque es un servicio pago y el contenido sale de la infraestructura propia. Las dos invocaciones y sus esquemas de salida son los mismos para ambos proveedores.

### 4.1. Puntos de invocación

| Invocación | Clase | Entrada | Prompt |
| --- | --- | --- | --- |
| Extracción de documento | `DocumentAnalyzerImpl` | Una imagen por documento o por página de PDF (hasta 5 páginas, a 150 DPI), más el catálogo de hechos generadores del ramo y los atributos del bien que declara la póliza | `extraccion-documento-v9` |
| Clasificación del expediente | `ClaimClassifierImpl` | Solo texto: datos del expediente, reglas de la aseguradora, hallazgos de las reglas, historial del asegurado y resultados de las extracciones | `classification-v9` |

La versión del prompt de clasificación la fija `arbiter.llm.prompt-version` y queda registrada en `llm_analysis.prompt_version`, de modo que cada recomendación puede rastrearse hasta el prompt exacto que la produjo. El prompt de extracción está fijado en `DocumentAnalyzerImpl` y no se registra por análisis.

### 4.2. Conexión y autenticación

La llamada se hace con el SDK Google Gen AI para Java (`com.google.genai`) contra Vertex AI, no contra la API para desarrolladores. La autenticación usa Application Default Credentials de una cuenta de Google Cloud, sin clave de API. La elección es deliberada: una clave apuntaría a la API para desarrolladores, que tiene otra política de tratamiento de datos.

### 4.3. Parámetros

| Parámetro | Valor | Variable de entorno | Fundamento |
| --- | --- | --- | --- |
| Modelo | `gemini-3.5-flash` | `GEMINI_MODEL` | — |
| Proyecto | Proyecto de Google Cloud del equipo | `GOOGLE_CLOUD_PROJECT` | — |
| Región | `global` | `GOOGLE_CLOUD_LOCATION` | Gemini 3 solo se sirve desde `global`, `us` o `eu`. `global` no controla dónde residen los datos. |
| `maxOutputTokens` | 4096 | `GEMINI_MAX_OUTPUT_TOKENS` | Corta una generación descontrolada, que se factura por token. |
| `thinkingConfig.thinkingLevel` | `MINIMAL` | — | Gemini 3 no permite desactivar el razonamiento interno. Sus tokens consumen el tope de salida y, en documentos largos, dejaban el JSON cortado. Ambas invocaciones lo piden así. |
| `responseMimeType` | `application/json` | — | La respuesta se valida sin interpretar texto libre. |
| `responseJsonSchema` | Ver 4.4 | — | Se usa JSON Schema y no `responseSchema`, cuyo subconjunto OpenAPI no admite tipos como `["string","null"]`. |
| Tipo de imagen | `image/png` | — | Las imágenes llegan en base64 sin tipo declarado y se envían todas con este tipo. Las páginas de PDF se convierten a PNG. |
| `temperature`, `topP`, `topK` | Sin configurar (valores por defecto del modelo) | — | — |
| Tiempo de espera | 90 s | `GEMINI_TIMEOUT` | Una llamada normal responde en 3 a 7 s. El tope incluye los reintentos propios del SDK. |
| Ventana de contexto | 1.000.000 tokens | `GEMINI_CONTEXT_WINDOW` | Solo se usa para advertir en el log si el prompt la excede. |

Ante errores de E/S o tiempo de espera, `GeminiClient` reintenta una vez. Los errores 5xx los reintenta el SDK con espera progresiva. Si la falla persiste por un error 5xx o por falta de respuesta, la clasificación completa se repite: hasta 6 intentos en total, con espera inicial de 15 s, factor 2 y tope de 60 s por espera.

### 4.4. Esquemas de salida

Los esquemas se arman en cada solicitud con datos de la aseguradora activa. Por ejemplo, `suggestedClaimCause` es un enum con los hechos generadores del ramo de ese tenant, más el valor vacío: el modelo no puede devolver una causa que la compañía no tenga configurada. Si el ramo no tiene catálogo, el campo es texto libre.

**Clasificación del expediente** (todos los campos obligatorios):

| Campo | Tipo | Contenido |
| --- | --- | --- |
| `classification` | enum | `LLM_RECOMIENDA_APROBAR`, `LLM_NO_RECOMIENDA_APROBAR` o `LLM_SOLICITA_REVISION_MANUAL` |
| `factors` | array de string | Fundamentos que ve el analista |
| `confidence` | number de 0 a 1 | Confianza declarada |
| `causeConsistency` | enum | `MATCHES`, `AMBIGUOUS` o `CONTRADICTS`: coherencia entre relato y causa denunciada |
| `suggestedClaimCause` | enum | Causa que sugiere el relato, o vacío |
| `causeEvidence` | string | Evidencia de esa coherencia o contradicción |

Si el modelo devuelve una clasificación fuera de esas tres, la respuesta se rechaza. Un `causeConsistency` ausente o desconocido se toma como `AMBIGUOUS` y no rechaza la respuesta. `FAST_TRACK` y `FALTA_DOCUMENTACION` las asignan siempre las reglas, nunca el modelo. Las reglas también asignan `LLM_NO_RECOMIENDA_APROBAR` ante una denuncia prescripta y `LLM_SOLICITA_REVISION_MANUAL` ante una exclusión de cobertura, sin invocar al modelo.

**Extracción de documento**: `transcription` y `visualFindings` son obligatorios. Los datos de `fields` son opcionales: fecha, monto, descripción del bien, atributos del bien, parte afectada (`TITULAR`, `FAMILIAR`, `TERCERO` o `DESCONOCIDO`), causa descripta y detalles. Fecha, monto, descripción, atributos y causa admiten nulo, para que el modelo no invente lo que el documento no dice. Cada detalle exige nombre y valor. Una respuesta vacía o ilegible se reintenta una vez.

### 4.5. Protección de datos

Con Gemini activo, los prompts y las imágenes de los documentos salen de la infraestructura propia, y esto está alcanzado por la Ley 25.326. El código no seudonimiza los datos antes de enviarlos. La región `global` tampoco fija dónde residen.

## 5. Google Cloud Vision API

Vision API responde una sola pregunta: si la imagen adjunta a la denuncia ya está publicada en internet, por ejemplo como foto de stock o en un aviso de venta. Es la segunda etapa de la detección de reutilización de imágenes (ver 6.3) y solo se invoca para imágenes que no coinciden con ningún adjunto previo de la misma aseguradora. A Vision API se le envía solo la imagen.

Se ejecuta únicamente si se cumplen las tres condiciones siguientes:

- La integración está habilitada (`GOOGLE_VISION_ENABLED=true`; por defecto está deshabilitada).
- Hay una clave de API configurada.
- El asegurado dio su consentimiento para la consulta de imágenes.

Si falta el consentimiento, el expediente lo registra explícitamente («no se buscó en internet — el asegurado no dio su consentimiento»), para que el analista distinga «no se pudo buscar» de «no se encontró».

### 5.1. Solicitud

```json
POST https://vision.googleapis.com/v1/images:annotate?key=<GOOGLE_VISION_API_KEY>
{
  "requests": [{
    "image": { "content": "<imagen en base64>" },
    "features": [{ "type": "WEB_DETECTION", "maxResults": 10 }]
  }]
}
```

| Parámetro | Valor | Variable de entorno |
| --- | --- | --- |
| Habilitación | `false` | `GOOGLE_VISION_ENABLED` |
| Clave de API | Sin valor por defecto | `GOOGLE_VISION_API_KEY` |
| URL base | `https://vision.googleapis.com` | — |
| `features.type` | `WEB_DETECTION` | — |
| `features.maxResults` | 10 | `GOOGLE_VISION_MAX_RESULTS` |

### 5.2. Respuesta utilizada

| Campo de `webDetection` | Uso en ARBITER |
| --- | --- |
| `fullMatchingImages` | Cantidad de coincidencias exactas |
| `partialMatchingImages` | Cantidad de coincidencias parciales (recortes o ediciones) |
| `pagesWithMatchingImages` | URL y título de las páginas donde aparece; se muestran hasta tres al analista |
| `bestGuessLabels` | Descripción que Google asigna a la imagen (se toma la primera) |

`visuallySimilarImages` no se lee: una imagen parecida no prueba reutilización.

Un hallazgo se registra en la fila de `image_analysis` de esa imagen, con `external_source = google-vision`, `match_type` igual a `WEB_FULL` (si hay alguna coincidencia exacta) o `WEB_PARTIAL`, e `is_suspicious = true`, de modo que puede consultarse fuera del informe. Un error de Vision API no interrumpe la clasificación: la imagen queda sin búsqueda web.

## 6. Embeddings de imágenes

Los embeddings detectan si una imagen adjunta ya fue presentada en otro siniestro de la misma aseguradora. Cada imagen se convierte en un vector de 512 dimensiones con CLIP y se compara por similitud coseno contra los vectores almacenados en el esquema del tenant.

Se usa CLIP y no el modelo de visión de la clasificación porque CLIP es determinista: la misma imagen produce siempre el mismo vector, condición que exige la búsqueda por similitud.

### 6.1. Servicio de embeddings

Es un servicio auxiliar en Python (`embedding-service`, FastAPI) que carga el modelo `ViT-B-32` con pesos preentrenados `openai` mediante la biblioteca `open_clip`. Corre dentro de la infraestructura propia: las imágenes no salen del sistema.

| Elemento | Valor |
| --- | --- |
| Endpoint | `POST /embed` |
| Solicitud | `{ "image_base64": "<imagen>" }` |
| Respuesta | `{ "embedding": [512 números], "dims": 512, "model": "clip-vit-b-32-openai" }` |
| Normalización | El vector se normaliza a norma 1 antes de devolverse |
| Formatos admitidos | JPEG, PNG, WebP, BMP y GIF |
| Cliente | `ClipClient`: conexión 2 s, lectura 30 s y hasta 7 reintentos de conexión (unos 54 s), porque el servicio tarda entre 20 y 40 s en cargar el modelo al iniciar |

### 6.2. Almacenamiento y búsqueda en pgvector

El vector se guarda en `image_analysis.embedding`, de tipo `vector(512)`, dentro del esquema de cada aseguradora. Como la tabla existe una vez por tenant, la comparación nunca cruza compañías. La columna `model` registra con qué modelo se generó cada vector, porque vectores de modelos distintos no son comparables.

La búsqueda usa el operador de distancia coseno de pgvector (`<=>`), con un índice IVFFlat (`vector_cosine_ops`, `lists = 100`):

```sql
SELECT cd.case_id, cd.id, cd.type, cd.filename,
       1 - (ia.embedding <=> CAST(:queryVector AS vector)) AS similarity
FROM image_analysis ia
JOIN case_documents cd ON cd.id = ia.case_document_id
WHERE cd.case_id != :excludeCaseId
  AND 1 - (ia.embedding <=> CAST(:queryVector AS vector)) >= :threshold
ORDER BY similarity DESC
LIMIT :maxResults
```

| Parámetro | Valor | Variable de entorno |
| --- | --- | --- |
| URL del servicio | `http://localhost:8000` | `EMBEDDING_SERVICE_URL` |
| Modelo | `clip-vit-b-32-openai` | `EMBEDDING_MODEL` |
| Umbral de similitud | 0,90 | `EMBEDDING_SIMILARITY_THRESHOLD` |
| Máximo de coincidencias | 5 | `EMBEDDING_MAX_RESULTS` |

La búsqueda excluye el propio expediente, para que una imagen no coincida consigo misma ni con otros adjuntos del mismo caso, y se ejecuta antes de guardar el vector nuevo. Una coincidencia marca el análisis como sospechoso (`INTERNAL_DUPLICATE`) y guarda el adjunto más parecido y su similitud. El analista la ve como «Imagen '<tipo de documento>': 93% similar a un adjunto del siniestro #N ('<archivo>')».

### 6.3. Cascada de detección de imágenes reutilizadas

1. Se genera el embedding y se busca en los siniestros previos de la aseguradora.
2. Si hay coincidencia, se informa y no se consulta ningún servicio externo.
3. Si no la hay, y se cumplen las condiciones de la sección 5, se consulta Vision API.
4. Ninguna falla de esta cascada interrumpe la clasificación del expediente.

La cascada corre solo si se analizó la documentación del expediente. Una denuncia prescripta o excluida por cobertura no la ejecuta.

Los embeddings no tienen opción de desactivarse, de forma deliberada: sin ellos ninguna imagen tendría coincidencia interna y todas escalarían a la búsqueda externa.

## Fuentes

- Repositorio [Msoteras/arbiter](https://github.com/Msoteras/arbiter), rama `main`: `classification-service` (adaptadores, servicios, configuración y `application.yml`), `embedding-service/app.py`, `db/init-multitenant.sql`, `common-lib` (`InsurerDbSchema`, `RuleType`) y paquetes `config/tenant` de cada servicio.
- Documento de Arquitectura ARBITER, v1.0 (27/05/2026).
- Procedimiento Siniestros_NSIN001, BBVA Seguros Argentina (revisión 20/01/2025).
- [Gemini en Vertex AI: referencia de generateContent](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/model-reference/gemini)
