# Arbiter — Guía para Claude

**Proyecto Final UTN FRBA (DDSI · K5054 · Grupo 5303).** Sistema de gestión inteligente del ciclo de vida de **siniestros** con IA, pensado como **plataforma multi-aseguradora**. Foco actual: el **Módulo de Análisis y Clasificación** (clasificación preliminar del siniestro con LLM + revisión humana obligatoria).

Idioma: respondé y escribí commits/docs en **español rioplatense**. Identificadores de código y **comentarios dentro del código** van en **inglés**, sin excepción — incluidos los nombres de clases/tipos que antes quedaban en castellano (`Siniestro`→`Claim`, `Poliza`→`Policy`, `Asegurado`→`Insured`, `Aseguradora`→`Insurer`, `Regla`→`Rule`, `Clasificacion`→`Classification`, etc.).

La sección "Modelo de dominio — vocabulario" más abajo es la excepción: ahí los términos quedan en **español**, porque documentan el vocabulario de negocio real (relevamiento BBVA) tal como lo usa el analista — es prosa de negocio, no identificadores de código.

---

## Arquitectura — leer antes de tocar nada

**Monolito modular desplegado como varias instancias.** El documento de arquitectura lo llama "monolito modular" en sentido lógico (una sola aplicación cohesionada, sin dependencias externas tipo SaaS), pero la **topología de despliegue real** es:

- **5 instancias Spring Boot independientes**, una por módulo funcional (`auth`, `classification`, `cases`, `rules`, `reports`), corriendo en el **mismo servidor de aplicación (mismo host)** en puertos distintos.
- **Nginx adelante** como reverse proxy + TLS, ruteando por path al puerto correspondiente.
- **Cooperación entre módulos por REST interno** (HTTP plano, sin TLS, dentro del host). No es comunicación de red pública.
- `common-lib` provee los DTOs/enums/excepciones compartidos para que los contratos REST entre módulos no se desincronicen, más las entidades JPA del esquema común (`arbiter_common`), que son de la plataforma y no de un módulo.

### Puertos asignados

| Módulo                | Puerto |
|-----------------------|--------|
| `auth-service`        | 8080   |
| `rules-service`      | 8081   |
| `classification-service`  | 8082   |
| `cases-service` | 8083   |
| `reports-service`    | 8084   |
| `embedding-service`   | 8000   |
| `arbiter-frontend`    | 4200   |

Los cinco módulos Java los fijan como `${PORT:80xx}` en su `application.yml`: el literal es el puerto
local y `PORT` es lo que inyecta Railway al desplegar.

**El 4200 lo pelean dos cosas.** `ng serve` y el frontend containerizado publican ahí, y no conviven:
`ng serve` toma `[::1]:4200` y Docker `[::]:4200`, y como Windows resuelve `localhost` a IPv6 primero,
con los dos arriba el navegador te sirve el **dev server** creyendo que mirás el contenedor — y como el
dev server proxea a los mismos puertos del back, no hay ningún síntoma. Levantá uno o el otro; si
necesitás los dos, `FRONTEND_PORT` mueve el del contenedor.

### Capas (sección 6 del documento de arquitectura)

| Capa             | Componente               | Tecnología                       |
|------------------|--------------------------|----------------------------------|
| **Cliente**      | SPA                      | Angular 20                       |
| **Integración**  | Reverse proxy + TLS      | Nginx                            |
| **Aplicación**   | Backend + LLM            | Java 21, Spring Boot 4.0.5, Ollama (Qwen3-VL) |
| **Persistencia** | BD Arbiter + BD Aseguradora | PostgreSQL (+ pgvector), JDBC/Spring Data JPA |

### Módulos del backend

| Módulo Maven           | Responsabilidad (según doc)                                                                   |
|------------------------|-----------------------------------------------------------------------------------------------|
| `common-lib`           | Tipos compartidos: DTOs, enums de dominio (`Classification`, `CaseStatus`, `RuleType`…), excepciones, y las **entidades JPA del esquema común** (`arbiter_common`). |
| `classification-service`   | **Módulo de Análisis y Clasificación** — orquesta: denuncia → Ollama → decisión del analista. |
| `cases-service`  | **Módulo de Expedientes** — ciclo de vida, transiciones, documentación adjunta.               |
| `rules-service`       | **Motor de Reglas de Negocio** — reglas cargadas dinámicamente desde BD, no en código.         |
| `reports-service`     | **Reportes y Estadísticas** — agregaciones, tableros para el referente.                       |
| `auth-service`         | **Gestión de Usuarios** — integración con Auth0, JWT, RBAC.                                   |
| `embedding-service`    | **Servicio de Embeddings** — sidecar Python/FastAPI con CLIP ViT-B-32 (512 dims). No es Maven ni Java: es el único módulo fuera del build de Maven. Lo consume `classification-service` por REST interno. |
| `arbiter-frontend`     | SPA Angular 20.                                                                                |

Estructura interna de cada módulo backend (ya scaffoldeada — respetala):

```
ar.edu.utn.frba.arbiter.<modulo>/
├── <Modulo>ServiceApplication.java
├── config/         # @Configuration, beans, WebMvc, security
├── controllers/    # @RestController — solo orquesta, sin lógica
├── dto/            # request/response (records preferidos)
├── exceptions/     # excepciones de dominio + @ControllerAdvice
├── models/
│   ├── entities/   # @Entity JPA
│   └── repositories/ # Spring Data JPA
└── services/       # lógica de negocio
```

---

## Modelo de dominio — vocabulario

Estos términos vienen del relevamiento de una aseguradora real (BBVA Seguros, API `segrest`) — no los inventamos nosotros. Usar **estos nombres** en entidades, DTOs y endpoints para que el analista de siniestros (usuario final) se encuentre con lo que ya conoce.

| Concepto             | Qué es                                                                                | Dueño         |
|----------------------|----------------------------------------------------------------------------------------|---------------|
| **`Ramo`**           | Línea de seguro (celulares, hogar, automotor, vida…). Configurable por aseguradora.   | `rules`       |
| **`Producto`**       | Variante comercial dentro de un ramo (ej. "Celular Protegido Básico").                | `rules`       |
| **`HechoGenerador`** | Causa del siniestro (robo en vía pública, hurto, caída, incendio…). Es el campo central que el LLM clasifica. | `rules` |
| **`BienAsegurado`**  | Bien cubierto por la póliza (un Samsung A56 específico, un auto patentado…).          | `classification` |
| **`Poliza`**         | Contrato (nro, certificado, endoso, vigencia, tomador, asegurado, productor, prima).  | `classification` |
| **`Cobertura`**      | Riesgo cubierto en la póliza con su suma asegurada y franquicia.                       | `classification` |
| **`Clausula`/`Anexo`** | Condiciones particulares aplicables (códigos: 100, 101, 102, 105, 340, 344…).      | `classification` |
| **`Siniestro`**      | El hecho denunciado (vincula Póliza + HechoGenerador + Bien + fecha + descripción).   | `classification` |
| **`Denuncia`**       | Acto de carga del siniestro (con adjuntos, geolocalización, denuncia policial).       | `classification` |
| **`Expediente`**     | Caso administrativo derivado del siniestro, con estado y trazabilidad.                | `cases`       |
| **`AgendaDocumental`** | Lista de **documentos requeridos** según `Ramo` + `HechoGenerador`. Determina si el expediente está completo. Configurable por aseguradora. | `rules` (definición) + `cases` (instancia por expediente) |
| **`Adjunto`**        | Archivo subido por el asegurado (PDF, imagen). Cumple un item de la `AgendaDocumental`. | `cases`       |
| **`Clasificacion`**  | Resultado del análisis (`FAST_TRACK` / `FALTA_DOCUMENTACION` / `LLM_RECOMIENDA_APROBAR` / `LLM_NO_RECOMIENDA_APROBAR` / `LLM_SOLICITA_REVISION_MANUAL`) + factores. Solo `FAST_TRACK` es determinístico (gate de reglas, no LLM); los otros 4 son recomendaciones no vinculantes del LLM. | `classification` |
| **`ClasificacionLog`** | Registro inmutable y auditable de cada clasificación (input, output, decisión). En el esquema: `llm_analysis`, `rule_result` y `case_classification`. | `classification` |

### Implicancias de diseño

1. **El alta de un siniestro NO es un único POST.** Es un wizard con catálogos en cascada — así lo hace BBVA y así lo necesita el usuario:
   ```
   1. listar Productos habilitados para el asegurado
   2. para la Póliza elegida → consultar HechosGeneradores válidos
   3. para Ramo + HechoGenerador → consultar Bienes asegurables
   4. para Ramo + HechoGenerador → consultar AgendaDocumental requerida
   5. autocompletar Domicilio de riesgo + Datos filiatorios
   6. POST alta de Denuncia (con descripción + adjuntos)
   ```
   El frontend tiene que pedir cada paso y mostrar las opciones del siguiente. El backend expone endpoints de catálogo en `rules-service` (`GET /api/v1/rules/branches`, `GET /api/v1/rules/claim-causes`, etc.).

2. **El LLM clasifica mejor con campos estructurados.** El prompt no recibe solo texto libre de la denuncia: recibe `{ ramo, producto, hechoGenerador, bien, descripcionLibre, adjuntosOCR, imagen }`. Los campos estructurados son contexto duro que ancla la inferencia.

3. **La AgendaDocumental es el contrato de "expediente completo".** Antes de pasar el expediente al analista, `cases-service` valida contra la agenda que todos los documentos obligatorios estén subidos. Si faltan, el estado es `AWAITING_DOCUMENTATION`, no `PENDING_ANALYST_REVIEW` (los valores del enum `CaseStatus` van en inglés; el label en español es cosa del frontend).

4. **El plazo del art. 56 se congela en ciertos estados, y eso lo decide `CaseStatus.pausingTheTerm()`.** Hoy son `AWAITING_DOCUMENTATION`, `PENDING_EXPERT_REPORT` y `PENDING_REPAIR`: el expediente está esperando a alguien de **afuera** de la aseguradora (el asegurado por documentación, el perito por su informe, el servicio técnico por el equipo), y el procedimiento de la compañía dice que eso "interrumpe el plazo para que la Aseguradora se expida". Ese tiempo corre para el asegurado pero no se le imputa a la gestión.
   - Vive en `common-lib` y no en `cases-service` **porque lo responden dos módulos**: `cases-service` congela con eso la fecha límite, y `reports-service` lo usa para separar, en el tiempo promedio de resolución, lo que tardó la compañía de lo que tardó esperando a un tercero. Dos listas separadas se desincronizan, y el día que pase el tablero y el semáforo de plazos se van a contradecir sin que nadie lo note.
   - `PENDING_REPAIR` (derivación a servicio técnico) es un estado **no final**: el expediente vuelve a `PENDING_ANALYST_REVIEW` y el analista sigue decidiendo.

5. **Reglas duras vs clasificación del LLM.** Las exclusiones de cobertura (ej. "el bien estaba fuera del campo visual" → no cubierto; "ocurrió en domicilio declarado" → no cubierto) son **reglas evaluables** en `rules-service`, no decisiones del LLM. El LLM aporta la lectura interpretativa (¿la denuncia describe un robo o un hurto? ¿la imagen es coherente con lo narrado?); las reglas evalúan condiciones objetivas.

### Referencias para el modelo

- **Póliza modelo (BBVA, ramo celulares)**: `Proyecto Final/poliza.pdf` — referencia para campos de `Poliza`, `Cobertura`, `Clausula`, `BienAsegurado`.
- **API real de BBVA (QA)**: HAR capturado de `desa1-qa.bbvaseguros.com.ar` con 16 endpoints de `segrest/api/ext/siniestros`. Los códigos `ramCod`, `prdCod`, `hegCod`, `biesCod`, `spolNum` mapean directo a las entidades de la tabla. No replicar la API (es referencia de vocabulario, no de contrato).
- **Disposición SSN 2/2023**: justificación regulatoria del human-in-the-loop y la auditoría de clasificaciones.

---

## Decisiones de arquitectura inmutables

Estas decisiones están **cerradas y aprobadas** (doc v1.0, 27/05/2026). No las cuestiones ni propongas alternativas salvo que el equipo lo abra explícitamente.

1. **LLM en infraestructura propia con Ollama + Qwen3-VL.** No usamos Anthropic ni OpenAI. Razón: privacidad de datos, sin costo por token, licencia Apache 2.0.
   - **Ollama sigue siendo el default y es lo que describe el documento de arquitectura.** `OllamaClient` está anotado `matchIfMissing = true`: si nadie dice nada, es el que se cablea.
   - **`GeminiClient` (Gemini por Vertex) existe como implementación alternativa** detrás de la interfaz `LlmClient`, **opt-in** con `arbiter.llm.provider=gemini` (`LLM_PROVIDER` en el `.env`). Es el mismo patrón Adapter que ya usamos para Auth0 y SendGrid, y está para cuando la máquina no da con el modelo local. No lo pongas por default: el entorno de nadie tiene que empezar a pegarle a una API paga porque bajó una rama.
   - **`GoogleVisionClient` (Web Detection)** es la única llamada a un tercero en el flujo de clasificación, y está **apagada por default** (`GOOGLE_VISION_ENABLED=false`). Solo se consulta si la comparación interna por pgvector no encontró coincidencia, y **solo se le manda la imagen**, nunca el expediente ni datos personales.
2. **Ventana de contexto Ollama fijada en 32.768 tokens** (configurar explícitamente; Ollama descarta en silencio lo que pase). Prompt típico estimado 6–15k tokens.
3. **Una sola instancia del modelo para todas las aseguradoras.** La especialización por compañía se hace **en el prompt** (inyectando las reglas), nunca con fine-tuning ni con un modelo por aseguradora.
4. **Clasificación asincrónica.** El registro de la denuncia encola la inferencia hacia Ollama; el analista la consulta después. Objetivo: clasificación disponible **<30 min** desde la denuncia (el número del documento de arquitectura v1.2, §7 — CLAUDE.md decía 10 y era la desincronización más vieja de las dos).
5. **Human-in-the-loop obligatorio.** Toda clasificación del modelo requiere **aprobación o rechazo de un analista** antes de impactar en el expediente. **No hay** resolución automática — ni siquiera para Fast Track. El Fast Track agiliza, no automatiza.
6. **5 categorías de clasificación** en `Classification` (`common-lib`): `FAST_TRACK` (determinístico, decidido por `FastTrackValidator` con reglas de negocio — el LLM **nunca** puede devolver este valor), `FALTA_DOCUMENTACION`, `LLM_RECOMIENDA_APROBAR`, `LLM_NO_RECOMIENDA_APROBAR`, `LLM_SOLICITA_REVISION_MANUAL`. Los 4 valores con LLM son recomendaciones no vinculantes — el analista decide siempre (ver punto 5).
7. **Auditoría completa de cada clasificación** (Disposición 2/2023). Persistir, en una tabla aparte e inmutable: resultado del modelo, factores que lo fundamentan, decisión del analista, marca temporal. 100% de las clasificaciones deben tener este registro.
8. **Auth0 + JWT + RBAC.** Tres roles: `ASEGURADO`, `ANALISTA_SINIESTROS`, `REFERENTE_ASEGURADORA`.
   - **Auth0 integrado y funcionando** (`Auth0Adapter` detrás de la interfaz `CredentialsAuthenticator`, `AUTH_PROVIDER=auth0`) — probado de punta a punta: invitación real por SendGrid, el usuario elige su propia contraseña, login valida contra Auth0.
   - **`Auth0Adapter` es hoy la ÚNICA implementación de `CredentialsAuthenticator`.** El `DatabaseCredentialsAuthenticator` (BCrypt local) que figuraba acá como alternativa **ya no existe**: el esquema multi-tenant no tiene columna `password_hash`, así que Auth0 es el único lugar donde vive una contraseña. `AUTH_PROVIDER=database` no es un camino válido — sin Auth0 configurado no hay login.
   - **Alta de analistas:** funcionando (invitación por SendGrid, el usuario setea su contraseña). El formulario del panel de usuarios **solo** crea `ANALISTA_SINIESTROS`; el backend rechaza cualquier otro rol.
   - **Alta de asegurados:** en bloque, disparada por el referente (`POST /api/v1/auth/users/insured/bulk-provision`). Lee los asegurados con póliza vigente de la BD Aseguradora y les provisiona la cuenta + la invitación. **No es un ABM**: la identidad del asegurado es dato de la compañía (decisión #10), así que nadie la tipea en Arbiter. El de-dup es **por email** — la misma persona asegurada en dos compañías es un solo login con dos filas en `user_insurer`, y vincular la aseguradora es lo que le suma sus pólizas. No hay autoservicio.
9. **SendGrid** para mail (notificaciones de cambio de estado al asegurado).
10. **PostgreSQL** con **multi-tenant por esquema separado por aseguradora** dentro de la misma instancia. NO hacer discriminación por columna `tenant_id`, NO instancia por aseguradora. La BD de la aseguradora (pólizas, historial) es **otra base** integrada por base de datos compartida.
    - **Cómo se materializa la integración (confirmado con Aylén, 31/7):** Arbiter persiste **snapshots locales** de lo que le pasa la BD Aseguradora (`Poliza`, `Cobertura`, `BienAsegurado`, `Asegurado`) — no se consulta la BD externa en vivo en cada request. Un cron (o consulta a demanda) trae los datos y los mapea a las entidades propias de Arbiter. Sin esto, Arbiter no funciona de forma autónoma/consistente. Esto no contradice "integración por base de datos compartida": es el mecanismo concreto que la implementa.
11. **pgvector** para detectar imágenes reutilizadas entre denuncias (similitud coseno de embeddings). **No** delegues esta comparación al modelo de visión: el LLM analiza solo la imagen del siniestro en curso.
    - **Ya está decidido e implementado: CLIP ViT-B-32 en el sidecar `embedding-service`** (Python/FastAPI), 512 dimensiones por imagen. `classification-service` lo consume por REST interno y persiste los vectores en PostgreSQL. Esto ya no es una opción abierta.
    - Si la comparación interna **no** encuentra coincidencia, recién ahí se puede consultar Google Vision (ver decisión #1).
12. **Reglas de negocio dinámicas en BD**, administradas por el referente. **No** implementar Strategy en código para variar por aseguradora — eso requiere redeploy por cada cambio.
13. ~~**API REST stateless.**~~ **Quitada el 31/08/2026:** el chat entrega por WebSocket. Sigue vigente lo que citan los `SecurityConfig`: sesión en el JWT, sin sesión de servidor ni CSRF. Con el broker en memoria, `cases-service` corre en una sola instancia.
14. **Nginx como reverse proxy + terminación SSL.** Centraliza certificados. El backend no se expone directo a internet.
15. **Despliegue en Docker** sobre Railway/AWS. RDS para Postgres, S3 (+ Glacier a 30d, borrado a 180d) para adjuntos, ECR para imágenes.

### Patrones de diseño confirmados

- **Repository** (Spring Data JPA) para acceso a datos.
- **Adapter** para integraciones externas: implementá `Auth0Adapter`, `SendGridAdapter`, `OllamaAdapter` que encapsulen al SDK del proveedor. La lógica de negocio depende del adapter, no del SDK.
- **MVC** clásico de Spring (controllers / services / repositories).
- **Arquitectura en capas** + **monolito modular** + **multi-tenant by schema**.

---

## Stack

- Java **21** (virtual threads activadas: `spring.threads.virtual.enabled: true`). Pensá los servicios como bloqueantes "tradicionales" — no metas WebFlux/Reactor.
- Spring Boot **4.0.5**, Spring Cloud **2025.1.1** (BOM en POM padre — no fijes versión en submódulos).
- Spring Data JPA + JDBC (PostgreSQL driver). **No usamos Flyway/Liquibase**: el esquema lo define `db/init-multitenant.sql` a mano (todas las tablas, un solo script) y **`hibernate.ddl-auto: validate`**, nunca `update`. Con el `search_path` multi-tenant, en `update` Hibernate recrea las tablas de `arbiter_common` adentro de cada esquema de aseguradora apenas no las encuentra calificadas. Los ITs lo pisan a `update` en su `AbstractPersistenceIT` porque levantan contra un contenedor vacío donde nadie corre el script.
- Lombok activado vía annotation processor.
- **Swagger / SpringDoc** para la documentación de la API REST — agregar el starter en cada módulo con controllers.
- Frontend: Angular 20 (standalone components, signals, `ChangeDetectionStrategy.OnPush`), SCSS. **Diseño responsive obligatorio** (RNF de usabilidad: ≥85% éxito en tareas básicas en PC y móvil).
- Ollama local con Qwen3-VL, contexto 32.768.

---

## Design System del frontend — usarlo SIEMPRE

El frontend (`arbiter-frontend/`, Angular 20 standalone + signals + OnPush) tiene un **design system propio ya construido**. Todo trabajo de UI —pantalla nueva, componente nuevo, ajuste— **debe** apoyarse en él. **No** traigas Tailwind, no uses OKLCH, no inventes una paleta ni reimplementes primitivas que ya existen.

### Las 3 capas de estilos (fuente de verdad)

| Capa | Archivo | Qué contiene | Regla |
|------|---------|--------------|-------|
| **1 · Primitivos** | `src/styles/_tokens.scss` | valores crudos: paleta `--c-*` (neutros cálidos "papel"), `--accent` (teal de marca) + familia `--accent-strong/soft/soft-border/veil`, technicolor `--accent-green/yellow/orange/red/blue`, `--space-1..7`, `--font-size-2xs..xl`, `--font-weight-regular/medium/bold`, `--radius-ctl/card/modal/pill`, sombras/overlay. | El **único** lugar donde pueden vivir hex/px crudos. No consumir estos tokens directo desde componentes. |
| **2 · Semánticos** | `src/styles/_semantic.scss` | roles: `--text-primary/secondary/tertiary/muted/on-emphasis`, `--surface`/`-soft`/`-sunken`/`-head`, `--border-subtle/default/control/strong`, `--action-primary-bg`/`-bg-hover`/`-fg` (botón primario **oscuro**), `--action-secondary-bg`/`-fg`/`-border`/`-border-hover`, el **acento de marca** `--accent-fg`/`--selected-bg`/`--selected-border`/`--border-focus`/`--focus-ring` (estados activos, selección, foco), y el **semáforo de estado** `--status-ok/warning/risk/danger/info` (mapean a la technicolor). | **Los componentes consumen SOLO estos.** Acá vive el "tema" (dark mode / branding por aseguradora se resolverían acá sin tocar componentes). |
| **Tipografía** | `src/styles/_typography.scss` | clases utilitarias: `.t-page-title`, `.t-section-label`, `.t-field-label`, `.t-body`, `.t-note`, `.mono`; utilidades `.measure` (~68ch), `.tabular`, `.sr-only`. | Usar SIEMPRE una clase `.t-*` en vez de setear `font-size`/`font-weight` sueltos. |

Los partials se cablean en `src/styles.scss` vía `@use`. **Guardrail del proyecto: prohibido hex/px crudos fuera de `_tokens.scss`.**

### El kit de componentes (`src/app/shared/ui/`)

Antes de escribir markup de UI, usá los componentes que ya existen en vez de rearmarlos a mano:

| Selector | Uso | Variantes / inputs clave |
|----------|-----|--------------------------|
| `app-button` | todo botón/acción | `primary` \| `secondary`; `block`, `disabled`, `type` |
| `app-badge` | chips de estado/etiqueta | `solid` \| `strong` \| `dashed`; `tone` (semáforo `ok`/`warning`/`danger`/`info` → punto de color) |
| `app-card` | contenedor de contenido | `heading`, `icon`, variante `soft` |
| `app-input` | campo de texto | (ver componente) |
| `app-textarea` | texto multilínea | (ver componente) |
| `app-modal` | diálogos / overlays | (ver componente) |
| `app-fraud-gauge`, `app-severity-label`, `app-empty-state`, `app-status-timeline`, `app-doc-upload` | componentes de dominio ya construidos | — |

### Reglas accionables

1. **Usá el kit, no reimplementes.** Botones, badges, cards, inputs, textareas y modales salen de `app-button`, `app-badge`, `app-card`, `app-input`, `app-textarea`, `app-modal`. Nada de `<button>`/`<div class="card">`/`<input>` estilizados a mano.
2. **Nunca valores crudos en componentes.** Prohibido hex y px sueltos fuera de `_tokens.scss`. Consumí tokens **semánticos** (`--text-primary`, `--surface`, `--border-control`, `--action-primary-bg`…), no primitivos `--c-*` ni `--accent` directo.
3. **Espaciado con `--space-*`, tipografía con clases `.t-*` + escala `--font-size-*`, radios con `--radius-*`.** Nada de márgenes/paddings/tamaños ad-hoc.
4. **Estética del proyecto: neutros cálidos ("papel") + teal de marca con criterio + semáforo solo para estado.** (Referencia visual: `docs/prototipo/arbiter-hifi.html`.) El acento de marca es `--accent` (teal) y se reserva a **estados activos, selección y foco** (stepper, tab activo, toggle, timeline, anillo de foco) vía los roles `--accent-fg`/`--selected-*`/`--border-focus`/`--focus-ring` — el botón primario es **oscuro** (`--action-primary-*`), no teal. La paleta technicolor (`--accent-*`) NO se usa cruda en componentes: se consume vía los roles semánticos `--status-ok/warning/risk/danger/info`, y **solo para comunicar estado** (semáforo de expediente/clasificación, texto de error), siempre sobrio — un punto, un borde o el color del texto, nunca fondos saturados. El tono sale del dominio: `estadoTone()` / `clasificacionTone()` (`core/models/`) mapean cada enum a su `StatusTone`. El `app-fraud-gauge` usa el semáforo `--status-*` para el nivel de riesgo (bajo→ok, medio→warning, alto→risk, crítico→danger). **La severidad textual (`app-severity-label`) se codifica por PESO + triángulo ▲, sin color.** No metas color nuevo sin acordarlo.
5. **Componente nuevo → sumalo a la styleguide.** Si agregás algo al kit, mostralo en la página `/styleguide` (`src/app/features/styleguide/styleguide.component.ts`). Es la vitrina viva del sistema.
6. **Antes de crear UI nueva, revisá `/styleguide` y `shared/ui/`.** Si ya existe, reusá; no dupliques.
7. **Accesibilidad y tipografía.** Los títulos de card son headings reales (jerarquía correcta, no `<div>` con estilo). Inputs a **16px en mobile** (evita el zoom de iOS). Contraste **AA** (la paleta ya está calibrada a 4.5:1). Mantené `.sr-only` para texto solo-lector y foco visible.
8. **Textos de UI cortos.** Avisos, tooltips, aclaraciones y factores que lee el analista: **una frase**, lo que pasó y, solo si no es obvio, qué hacer. No repitas lo que ya dice el título o el badge, no aclares lo que el usuario no preguntó ("esto no cambia la clasificación", "por sí solo no prueba nada") y no enumeres entre paréntesis. Ej.: bajo el título "Para revisar antes de resolver", ~~"El modelo notó señales de posible adulteración. Nada de esto cambia la clasificación: revisalo antes de decidir."~~ → "Hay señales de adulteración en la documentación."

---

## Requisitos No Funcionales — números que importan

Estos no son sugerencias, son métricas que tenemos que cumplir:

| Atributo         | Métrica                                                                                       |
|------------------|-----------------------------------------------------------------------------------------------|
| Disponibilidad   | **99,5%** en horario laboral. ≤2,2 h/mes de inactividad.                                      |
| Rendimiento      | Clasificación lista en **<30 min** desde la denuncia (determinística o del LLM).               |
| Seguridad        | JWT + TLS + RBAC. **100%** de clasificaciones auditadas con factores + decisión + timestamp.  |
| Mantenibilidad   | Nuevo ramo de seguros incorporado en **≤1 sprint (2 semanas)** vía configuración del motor de reglas (sin tocar código). |
| Usabilidad       | ≥85% éxito en tareas básicas (PC y móvil).                                                    |
| Escalabilidad    | Con 3 aseguradoras simultáneas, el tiempo de respuesta no se degrada >20% vs. 1 aseguradora.  |
| Continuidad      | **RPO 5 min · RTO 2 h.** Snapshots RDS diarios + PITR. Versionado S3 para adjuntos.            |

---

## Comandos clave

Desde la raíz del proyecto:

```bash
mvn clean install                                    # construye todo (common-lib primero)
mvn spring-boot:run -pl classification-service           # corre el módulo (revisar la cuestión del monolito antes)
mvn -pl classification-service -am package               # construye módulo + dependencias
mvn -pl classification-service test                      # tests del módulo

cd arbiter-frontend && npm install && npm start      # ng serve → http://localhost:4200

# Docker: contexto SIEMPRE en la raíz (multi-módulo necesita el POM padre + common-lib)
docker build -t classification-img -f classification-service/Dockerfile .
```

Ollama (para dev local):

```bash
ollama pull qwen3-vl:8b-instruct                     # primera vez (ver nota: instruct, NO el tag pelado)
ollama serve                                          # default: http://localhost:11434
# Configurar el contexto a 32k vía Modelfile (PARAMETER num_ctx 32768)
```

> **Usar `:8b-instruct`, no `qwen3-vl` a secas.** El tag pelado resuelve a `qwen3-vl:8b-thinking`,
> que razona antes de cada respuesta y **no se le puede apagar**: se probó contra Ollama 0.30.8 que
> ignora tanto `think: false` en la request como la directiva `/no_think` de Qwen. Los tokens de
> razonamiento van a `message.thinking`, no a `message.content`, así que consumen el presupuesto de
> `num_predict` sin aportar a la respuesta — con el tope en 4096 el modelo se quedaba pensando y
> devolvía **0 caracteres** después de 27 minutos por documento. La variante `-instruct` es el mismo
> modelo (8B, Q4_K_M, 6.1 GB, visión) sin esa fase. Para transcribir un documento el razonamiento no
> aporta: el JSON schema ya fuerza la forma de la salida.

---

## Convenciones que SÍ aplico

- **Records para DTOs y eventos**. Inmutables, sin Lombok salvo `@Builder` cuando hay muchos campos opcionales.
- **Constructor injection siempre** (`@RequiredArgsConstructor`). Nunca `@Autowired` en campos.
- **Excepciones de dominio** en `exceptions/` + un `@RestControllerAdvice` por módulo que las traduce a `ProblemDetail` (RFC 7807).
- **Endpoints REST en plural y kebab-case si aplica**: `/api/v1/cases`, `/api/v1/cases/{caseId}/fraud-record`.
- **Tipos compartidos** (DTOs públicos entre módulos, enums de dominio, excepciones base) van a `common-lib`. Lo interno de un módulo NO.
- **Las entidades JPA del esquema común van a `common-lib`**, en `common/models/entities/`. Son las 10 tablas de `arbiter_common` (`insurer`, `users`, `user_insurer`, `role`, `permission`, `role_permission`, `user_role`, `branch`, `claim_cause`, `case_status`): no son de ningún módulo, son de la plataforma, y varios módulos necesitan leerlas. Definirlas una sola vez evita que se desincronicen — `Insurer` llegó a estar duplicada en `auth-service` y `rules-service`. **Los repositories NO se comparten**: cada módulo declara el suyo con las queries que necesita, apuntando a la entidad de `common-lib`.
- **Las entidades de un esquema de aseguradora son del módulo dueño**, con una excepción acotada: cuando **más de un módulo** necesita la misma tabla de tenant, va a `common/models/entities/tenant/` (ver el `package-info` de ese paquete). Hoy la única es `Insured`, que auth-service y cases-service declaraban por separado y ya habían divergido. La distinción entre los dos paquetes importa: el padre son tablas con **una sola fila para toda la plataforma**; `tenant/` son tablas que existen **una vez por aseguradora**, y qué fila se lee depende del tenant resuelto. No sumes entidades ahí por las dudas: si la usa un solo módulo, va en ese módulo.
- **Tests**: JUnit 5 + Spring Boot Test. Testcontainers para PostgreSQL y, cuando aplique, para Ollama. Mockito para mocks.
- **Trazabilidad de cada clasificación**: registro inmutable, separado del expediente, en las tablas de `classification-service` (`llm_analysis` con modelo, `prompt_version`, salida y factores; `rule_result` con cada regla evaluada; `case_classification` con el resultado y la decisión del analista). No se actualizan: cada análisis es una fila nueva.
- **Multi-tenant**: cada request lleva el `tenant_id` (id de aseguradora) en el JWT. Resolver el esquema PostgreSQL en una `ConnectionProvider` o `Interceptor` de Hibernate al inicio del request. No hardcodear el schema en queries.
- **Comunicación entre módulos**: REST interno por HTTP (sin TLS, dentro del host). Cliente: `RestClient` de Spring 6+ (no `RestTemplate`). DTOs del request/response en `common-lib`. Configurar URLs base por `application.yml` (`arbiter.rules-service.url`, `arbiter.classification-service.url`) con default a localhost. Timeouts cortos y manejo de error explícito — no asumir que el otro módulo siempre responde.
- **Propagar el JWT** entre módulos cuando una request es por cuenta de un usuario. El módulo destino valida con Auth0 igual que si viniera del frontend. Para llamadas sistema-a-sistema (jobs internos), evaluar service account o token de servicio aparte.
- **Naming de clases de servicio**: sin adjetivos ni prefijos que describan el mecanismo (`Real`, `Database`, `Default`, `Internal`). Si hay interfaz + implementación única, la implementación lleva sufijo `Impl` (ej. `CaseService` → `CaseServiceImpl`). Si hay varias implementaciones, nombrarlas por **lo que las diferencia funcionalmente**, no por tecnología (ej. `MockClaimClassifier` / `OllamaClaimClassifier`, no `RealClaimClassifier`).
- **Enum literals en inglés**. El mapeo a labels en español es responsabilidad exclusiva del frontend (ver `estado.ts` como referencia). No mezclar idiomas dentro de un mismo enum.

## Convenciones que NO quiero ver

- Lógica de negocio en `controllers/`. El controller arma el DTO y delega.
- Acoplar dos módulos por **base de datos compartida**. Cada módulo es dueño de sus tablas; el resto las consulta por REST. **Tres excepciones, y solo tres:**
  1. La **BD Aseguradora** se accede directo desde quien la necesita (es integración por BD compartida, así lo define la doc).
  2. El **esquema común** (`arbiter_common`) es de la plataforma, no de un módulo — sus entidades viven en `common-lib` y cualquier módulo puede leerlas.
  3. **`reports-service` lee por SQL directo las tablas de `cases-service` y `classification-service`** (`cases`, `case_status_history`, `case_settlement`, `expert_assessment`, `rule_result`, `llm_analysis`, `case_classification`). Es lo que dibuja el documento de arquitectura para este módulo (§3: Reportes conecta directo a PostgreSQL, sin enlace REST con Expedientes; §10: "La base de datos es compartida entre todos ellos"), y es lo que hace viable un reporte sobre un período entero: una query en vez de una llamada HTTP por expediente. Condiciones para que siga siendo aceptable: **solo lectura**, **JDBC plano sobre columnas nombradas y sin entidades** (así este módulo nunca reclama propiedad de esas tablas), y todo dentro del mismo esquema de tenant. Ver el javadoc de `ResolvedCaseRepository`, que además explica por qué la query corre sobre la conexión de Hibernate y no sobre una del pool.
- Llamar al SDK de Auth0 / SendGrid / Ollama directo desde un service. Pasá por el Adapter.
- `service.findById(...).orElse(null)` con `if (x == null)` después. Tirá la excepción de dominio.
- Wrappers innecesarios (`SiniestroWrapper`, `SiniestroHelper`, `SiniestroUtilService`).
- Adjetivos o prefijos de mecanismo en nombres de services (`DefaultX`, `RealX`, `DatabaseX`). Usar `Impl` si hay interfaz + implementación única; si hay varias, nombrar por diferencia funcional.
- Comentarios que repiten lo que dice el código. Solo cuando el **por qué** no es obvio.
- Backwards-compat / flags / código muerto "por si las moscas". Greenfield.
- **Reglas de negocio hardcodeadas**. Las reglas viven en BD, no en `if`s ni en clases `Strategy`.
- **Decisión automática del modelo impactando en el expediente**. Siempre hay analista en el medio.
- **UI a mano ignorando el design system**: hex/px crudos en componentes, botones/cards/inputs reimplementados, tokens primitivos `--c-*` consumidos directo. Ver "Design System del frontend".

---

## Foco actual: Módulo de Análisis y Clasificación

Flujo de extremo a extremo:

```
Asegurado registra denuncia (frontend, wizard con catálogos en cascada)
  └─> GET /api/v1/policies, /api/v1/claim-causes        (cases-service)
  └─> GET /api/v1/rules/branches, /rules/document-requirements   (rules-service)
  └─> POST /api/v1/cases (cases-service, multipart con adjuntos)
        ├─> persiste Expediente (PENDING_CLASSIFICATION) + adjuntos
        ├─> POST /api/v1/claims (classification-service, REST interno) → encola el análisis
        └─> 202 Accepted con el id

[async] classification-service (ClassificationOrchestrator)
  ├─> lee reglas de la aseguradora (rules-service) + póliza e historial (BD Aseguradora)
  ├─> gate determinístico: FastTrackValidator + evaluadores de reglas
  ├─> si no califica: OCR de adjuntos + embedding de la imagen (pgvector) + prompt con campos
  │   ESTRUCTURADOS (ramo, producto, hecho generador, bien, relato, OCR, imagen, reglas, historial)
  ├─> LlmClient (Ollama por default, Gemini opt-in) → valida la salida contra el JSON schema
  └─> persiste llm_analysis / rule_result / case_classification (inmutables)

cases-service (ClassificationRefreshScheduler) consulta GET /api/v1/claims/{caseId}
  └─> PENDING_ANALYST_REVIEW, o AWAITING_DOCUMENTATION si falta algo de la agenda documental

Analista revisa y decide (frontend)
  └─> POST /api/v1/cases/{caseId}/decision
        ├─> POST /api/v1/claims/{caseId}/decision (classification-service: auditoría de la decisión)
        ├─> transición de estado del Expediente
        └─> mail al asegurado vía SendGrid
```

### Dónde está cada cosa

- **Prompts versionados** en `classification-service/src/main/resources/prompts/`. La versión vigente la fija
  `arbiter.llm.prompt-version` (hoy `classification-v6`; el de OCR es `extraccion-documento-v7`) y se
  persiste en `llm_analysis.prompt_version`. Las versiones anteriores se conservan para poder auditar
  análisis viejos. Plantilla (markdown) separada de la inyección de datos: nada de concatenar strings.
- **Variables de entorno**: la lista completa y comentada está en `.env.example`. Nunca en un yml versionado.
- **Embeddings de imágenes**: CLIP ViT-B-32 en `embedding-service` (ver decisión #11).

---

## Cómo quiero que trabajes

- **Antes de tocar código nuevo**, leé el módulo entero (es chico) para no duplicar patrones.
- **Cambios chicos**: aplicalos directo. **Cambios grandes** (nuevo flujo, nueva tabla, nuevo endpoint): proponé el plan en 3-5 líneas primero.
- **Si tocás `common-lib`**, recordá que impacta a todos los módulos: `mvn -am verify` sobre consumidores.
- **Antes de inventar un contrato inter-módulo**, releé la sección "Arquitectura". Comunicación entre módulos del backend → REST interno con DTO en `common-lib`. Comunicación frontend ↔ backend → REST sobre HTTPS (Nginx termina TLS).
- **Si una decisión choca con este documento**, marcalo y avisame en vez de improvisar.
