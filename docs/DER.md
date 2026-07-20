# DER — Modelo de datos de Arbiter

> Este documento tiene dos partes. La primera ("Estado actual") es un punto de partida vivo,
> derivado directamente de las `@Entity` de cada módulo y del DDL en [`db/init.sql`](../db/init.sql)
> — no del vocabulario de dominio aspiracional de `CLAUDE.md`. Se actualiza a medida que se agregan
> entidades reales.
>
> La segunda ("Modelo objetivo") documenta el diseño de datos para la próxima tanda de historias
> (H0012–H0015: scoring, Fast Track, motor de reglas, multi-tenant), partiendo de un DER dibujado a
> mano por el equipo y completado acá. **Nada de esa parte está persistido todavía** —
> `rules-service` y `reports-service` siguen siendo scaffolding vacío (solo `.gitkeep`). Se marca
> explícitamente como tal para no confundirla con la primera parte.

## Estado actual (persistido hoy)

### Alcance de esta versión

Lo que hoy **persiste de verdad**: 6 tablas, repartidas en 3 módulos con tablas propias
(`auth-service`, `cases-service`, `classification-service`).

| No incluido todavía | Por qué |
|---|---|
| `Ramo`, `Producto`, `HechoGenerador`, `AgendaDocumental`, `Regla`, catálogos de scoring | `rules-service` existe como módulo Maven pero está vacío (solo `.gitkeep`) — no hay entidades para dibujar. Ver "Modelo objetivo" más abajo para el diseño planeado. |
| `Poliza`, `Cobertura`, `Clausula`, `BienAsegurado` | No son tablas propias de Arbiter. Hoy vienen de `MockInsurerAdapter` simulando la **BD Aseguradora** externa (decisión de arquitectura #10) — se integran por base de datos compartida cuando exista, no se modelan como entidades nuestras. |
| Tenant/aseguradora en `users` | `auth-service` sí existe y persiste usuarios reales (ver abajo), pero la entidad `User` no tiene ningún campo de aseguradora/tenant todavía — el multi-tenant por esquema (decisión de arquitectura #10) no está implementado. |

### Un matiz de arquitectura que el DER tiene que respetar

**No es un único schema.** `auth-service`, `cases-service` y `classification-service` son
aplicaciones Spring Boot independientes, cada una dueña de sus tablas — se consultan por REST
interno, no por join de base de datos (ver `CLAUDE.md`, sección Arquitectura). Por eso
`classification_log.case_id` **no es una FK real**: es una referencia lógica al id de un `Case` que
vive en otro módulo. Así lo dice el propio Javadoc de la entidad:

> *"This module does NOT persist the claim/case itself (...). The log only references the owning
> case by id. It's a historical record, not a navigable relationship."*

Por eso el diagrama de abajo está separado en bloques (uno por módulo) en vez de un único
`erDiagram` con todo unido — dibujar `classification_log }o--|| cases` como si fuera una FK más
sería mentir sobre una garantía que la base de datos no está imponiendo.

### Módulo `auth-service`

```mermaid
erDiagram
    USERS {
        bigint id PK
        varchar email "unique, not null"
        varchar password_hash
        varchar nombre
        varchar apellido
        varchar rol "enum UserRole: ASEGURADO | ANALISTA_SINIESTROS | REFERENTE_ASEGURADORA"
        varchar sector "nullable"
        date fecha_ingreso "nullable"
        varchar insured_id "nullable — DNI/id para atar la cuenta a su póliza real en BD Aseguradora"
        int failed_attempts
        timestamptz locked_until "nullable — seteado al 5º intento fallido consecutivo"
        timestamptz created_at
    }
```

**Notas:**
- Una sola tabla para los 3 roles (`rol` + `CHECK`), sin tabla de catálogo de roles ni de
  aseguradora — el multi-tenant-por-esquema (decisión #10) todavía no tocó esta entidad.
- `insured_id` es el único campo preparado para vincular un `ASEGURADO` a su registro real; queda
  `null` para `ANALISTA_SINIESTROS`/`REFERENTE_ASEGURADORA` (decisión #8 de `CLAUDE.md`).
- Sin relaciones JPA con `cases`/`classification_log` — el vínculo (¿quién es el analista de un
  expediente?) vive hoy como campos sueltos (`created_by`, `actor`, etc.), no como FK a `users`.

### Módulo `cases-service`

```mermaid
erDiagram
    CASES ||--o{ CASE_DOCUMENTS : "acumula adjuntos"
    CASES ||--o{ CASE_STATUS_HISTORY : "registra transiciones"

    CASES {
        bigint id PK
        varchar branch
        varchar product
        varchar claim_cause
        varchar insured_item
        varchar insured_id
        varchar policy_number
        text description
        timestamp event_date
        varchar event_location
        numeric claimed_amount "nullable"
        varchar status "enum CaseStatus"
        varchar analysis_classification "enum Classification, nullable"
        double analysis_confidence "nullable"
        text analysis_detail "nullable"
        boolean deterministic_fast_track "nullable"
        double risk_score "nullable — null distinto de LOW: 'sin scorear'"
        varchar risk_band "enum RiskBand, nullable"
        text risk_breakdown "JSON, nullable"
        text manual_adjustment_note "nullable"
        int classification_attempts
        timestamptz created_at
        timestamptz updated_at
    }

    CASE_DOCUMENTS {
        bigint id PK
        bigint case_id FK
        varchar type "police_report, item_photo, ... — UNIQUE(case_id, type)"
        varchar filename
        varchar content_type
        bytea content
        timestamptz uploaded_at
    }

    CASE_STATUS_HISTORY {
        bigint id PK
        bigint case_id FK
        varchar from_status "enum CaseStatus, nullable — null = alta del expediente"
        varchar to_status "enum CaseStatus"
        varchar actor "enum StatusChangeActor: SYSTEM | INSURED | ANALYST"
        varchar reason
        timestamptz changed_at
    }
```

**Notas:**
- `case_documents`: upsert por tipo — re-subir un `type` para el mismo `case_id` reemplaza el
  registro (constraint `UNIQUE(case_id, type)`), no lo duplica.
- `case_status_history`: append-only, sin updates ni deletes — es el rastro de auditoría de cada
  transición de estado, no una tabla mutable.
- `status` (`CASES`) usa los valores de `CaseStatus` (`common-lib`): `PENDING_CLASSIFICATION`,
  `PENDING_ANALYST_REVIEW`, `CLASSIFICATION_FAILED`, `AWAITING_DOCUMENTATION`, `APPROVED`, `REJECTED`.
- `analysis_classification` (`CASES`) y `classification` (`CLASSIFICATION_LOG`, abajo) comparten el
  mismo enum `Classification`: `FAST_TRACK`, `FALTA_DOCUMENTACION`, `LLM_RECOMIENDA_APROBAR`,
  `LLM_NO_RECOMIENDA_APROBAR`, `LLM_SOLICITA_REVISION_MANUAL`. En `CASES` es un **cache de lectura**
  del último resultado; el registro de auditoría autoritativo es `CLASSIFICATION_LOG`.

### Módulo `classification-service`

```mermaid
erDiagram
    CLASSIFICATION_LOG {
        bigint id PK
        bigint case_id "referencia lógica a CASES.id (otro módulo) — sin FK, sin @ManyToOne. Nullable: clasificaciones de prueba sin case detrás."
        varchar model "nullable — null cuando fue Fast Track por reglas"
        varchar prompt_version "nullable"
        varchar source "RULES_FAST_TRACK | LLM"
        varchar classification "enum Classification"
        numeric confidence "nullable"
        text factors "JSON array de strings"
        bigint latency_ms "nullable"
        numeric risk_score "nullable — snapshot inmutable al momento de clasificar"
        varchar risk_band "enum RiskBand, nullable"
        text risk_breakdown "JSON, nullable"
        varchar analyst_id "nullable hasta que el analista decide"
        varchar decision "nullable — APPROVE | REJECT"
        timestamp decision_timestamp "nullable"
        timestamptz created_at
    }
```

**Notas:**
- Es el registro inmutable de auditoría exigido por la Disposición SSN 2/2023: un `INSERT` por cada
  corrida de clasificación, nunca se actualiza el resultado del modelo — solo se completan
  `analyst_id`/`decision`/`decision_timestamp` cuando el analista decide (`recordAnalystDecision`).
- `risk_score`/`risk_band`/`risk_breakdown` son el **snapshot autoritativo** del scoring (H0012) en
  el momento exacto de esa clasificación; los mismos campos en `CASES` son una copia de lectura del
  último valor, para no pegarle a `classification-service` por REST cada vez que se lista o se
  muestra un expediente.

### Enums compartidos (`common-lib`, no son tablas)

Viven como `VARCHAR` + `CHECK`/`@Enumerated(STRING)`, no como tablas de catálogo — están fijos en
código, no son configurables desde BD (a diferencia de `Ramo`/`HechoGenerador`, que en el modelo
objetivo de abajo sí son catálogos en tablas propias).

- **`CaseStatus`**: `PENDING_CLASSIFICATION`, `PENDING_ANALYST_REVIEW`, `CLASSIFICATION_FAILED`, `AWAITING_DOCUMENTATION`, `APPROVED`, `REJECTED`
- **`Classification`**: `FAST_TRACK`, `FALTA_DOCUMENTACION`, `LLM_RECOMIENDA_APROBAR`, `LLM_NO_RECOMIENDA_APROBAR`, `LLM_SOLICITA_REVISION_MANUAL`
- **`RiskBand`**: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`
- **`UserRole`**: `ASEGURADO`, `ANALISTA_SINIESTROS`, `REFERENTE_ASEGURADORA`
- **`StatusChangeActor`** (vive en `cases-service`, no en `common-lib`): `SYSTEM`, `INSURED`, `ANALYST`

---

## Modelo objetivo — próxima iteración (rules-service, scoring persistente, multi-tenant)

> ⚠️ **Nada de lo que sigue está construido.** Es el diseño de datos para las historias H0012
> (Scoring de riesgo), H0013 (Fast Track), H0014 (Decisión asistida) y H0015 (Motor de reglas),
> completando el DER que trajo el equipo. Sirve para planificar `rules-service` y la evolución de
> `cases-service`/`classification-service`, no describe nada persistido hoy.

### Decisiones tomadas al completar este DER

Estas son las brechas que tenía el diagrama original y cómo se resolvieron (confirmado con el
equipo, no asumido):

1. **`regla_aseguradora` no tenía forma de saber a qué aseguradora pertenece.** Se le agregó
   `rama_aseguradora_id`, reusando el mismo patrón de habilitación que ya usaba
   `hecho_generador_aseguradora` (la regla queda scoped a una combinación aseguradora+rama ya
   habilitada), en vez de un `aseguradora_id` suelto.
2. **`estado_expediente` no tenía plazo regulatorio**, pese a que H0015 pide explícitamente que el
   referente configure "plazos máximos por estado (en días hábiles)". Se agregó
   `plazo_maximo_dias` (nullable — no todos los estados tienen plazo).
3. **`referente_aseguradora` y `analista_seguro` como tablas separadas** (en vez de la `users` única
   de hoy, con `rol` + `CHECK`) se documentan como el modelo objetivo, no como algo a migrar ya. Hoy
   esos dos roles son filas de `users` sin ningún campo de aseguradora; `asegurado` directamente no
   tiene tabla propia acá tampoco — sigue siendo una referencia externa (`asegurado_id`), igual que
   `insured_id`/`insuredId` hoy.

### ⚠️ Conflicto abierto: multi-tenant por columna vs. por esquema

Este modelo objetivo resuelve el multi-tenant con **columnas `aseguradora_id` y tablas puente**
(`rama_aseguradora`, `hecho_generador_aseguradora`, `regla_aseguradora`) dentro de un mismo esquema.
Eso **choca con la decisión de arquitectura #10 de `CLAUDE.md`**: *"multi-tenant por esquema
separado por aseguradora... NO discriminación por columna `tenant_id`, NO instancia por
aseguradora"*.

No se resuelve acá — queda documentado tal cual lo dibujó el equipo (parece la decisión más
reciente/pragmática) para que el equipo lo cierre explícitamente antes de empezar a construir
`rules-service`. Si se termina yendo por columna+tablas puente, `CLAUDE.md` decisión #10 tiene que
actualizarse para no quedar contradiciendo el código.

### Catálogo de reglas y ramos — futuro `rules-service`

```mermaid
erDiagram
    ASEGURADORA ||--o{ RAMA_ASEGURADORA : "habilita"
    RAMA ||--o{ RAMA_ASEGURADORA : "habilitada para"
    RAMA_ASEGURADORA ||--o{ HECHO_GENERADOR_ASEGURADORA : "combinaciones válidas de"
    HECHO_GENERADOR ||--o{ HECHO_GENERADOR_ASEGURADORA : "aplica en"
    COBERTURA ||--o{ HECHO_GENERADOR_ASEGURADORA : "aplica en"
    RAMA ||--o{ REGLA : "aplica a"
    REGLA ||--o{ REGLA_ASEGURADORA : "customizada por aseguradora en"
    RAMA_ASEGURADORA ||--o{ REGLA_ASEGURADORA : "scope de"
    CONFIGURACION_SCORING ||--o{ PESO_FACTOR : "pondera con"
    FACTOR ||--o{ PESO_FACTOR : "pesado en"

    ASEGURADORA {
        bigint id PK
        varchar razon_social
        varchar nombre
        varchar cuit
    }

    RAMA {
        bigint id PK
        varchar nombre "ej. celulares, hogar, automotor, vida"
    }

    RAMA_ASEGURADORA {
        bigint aseguradora_id PK, FK "PK compuesta junto con rama_id"
        bigint rama_id PK, FK
    }

    HECHO_GENERADOR {
        bigint id PK
        varchar nombre "ej. robo en vía pública, hurto, caída, incendio"
    }

    COBERTURA {
        bigint id PK
        varchar nombre
    }

    HECHO_GENERADOR_ASEGURADORA {
        bigint id PK
        bigint rama_aseguradora_id FK
        bigint hecho_generador_id FK
        bigint cobertura_id FK
    }

    REGLA {
        bigint id PK
        boolean para_fast_track
        varchar nombre
        bigint rama_id FK
    }

    REGLA_ASEGURADORA {
        bigint id PK
        bigint regla_id FK
        bigint rama_aseguradora_id FK "agregado — ver 'Decisiones tomadas' arriba"
        varchar respuesta "APROBAR | RECHAZAR | DERIVAR, sobre Classification"
        numeric umbral_desde
        numeric umbral_hasta
    }

    FACTOR {
        bigint id PK
        varchar nombre "ej. historial de siniestros previos, tiempo compra-denuncia, análisis forense, inconsistencias documentales, clasificación del siniestro (H0012)"
    }

    CONFIGURACION_SCORING {
        bigint id PK
        text formula
    }

    PESO_FACTOR {
        bigint factor_id PK, FK "PK compuesta junto con config_scoring_id"
        bigint config_scoring_id PK, FK
        numeric peso
    }
```

**Notas:**
- `hecho_generador_aseguradora.hecho_generador_id` — en el dibujo original el campo estaba nombrado
  `hecho_generador` a secas; se normalizó a `_id` acá por consistencia con el resto de las FK de la
  misma tabla (`rama_aseguradora_id`, `cobertura_id`), no porque cambie de significado.
- `cobertura` acá es un catálogo local liviano (`id`, `nombre`) usado solo para cruzar qué
  combinaciones rama+hecho_generador+cobertura son válidas por aseguradora. **No es** la `Cobertura`
  completa del vocabulario de `CLAUDE.md` (con suma asegurada y franquicia) — esa sigue viniendo de
  la BD Aseguradora externa, sin modelarse acá (mismo criterio que ya usaba la sección "Estado
  actual" para `Poliza`/`Clausula`/`BienAsegurado`).
- `regla.para_fast_track` marca si esa regla participa del circuito Fast Track (H0013); las que no,
  son reglas de exclusión/validación de cobertura evaluadas igual mediante el motor de reglas.
- `configuracion_scoring`/`peso_factor`/`factor` no tienen `aseguradora_id` — a diferencia de
  `regla`/`hecho_generador`, H0012 no pide scoring configurable por aseguradora (a diferencia de
  H0015, que sí es explícito sobre Fast Track/flujo configurable por aseguradora). Si esto cambia,
  agregar el scope ahí, no inventarlo acá.

### Usuarios por aseguradora — evolución objetivo de `auth-service`

```mermaid
erDiagram
    ASEGURADORA ||--o{ REFERENTE_ASEGURADORA : "emplea"
    ASEGURADORA ||--o{ ANALISTA_SEGURO : "emplea"

    REFERENTE_ASEGURADORA {
        bigint id PK
        varchar nombre
        varchar apellido
        bigint aseguradora_id FK
    }

    ANALISTA_SEGURO {
        bigint id PK
        varchar nombre
        varchar apellido
        varchar mail
        bigint aseguradora_id FK
    }
```

**Notas:**
- `ASEGURADORA` es la misma entidad del bloque anterior (`rules-service`) — se repite acá solo para
  que el diagrama de usuarios se lea sin scrollear al otro bloque.
- No hay tabla `asegurado`: sigue siendo una referencia externa (hoy `insured_id`/`insuredId` en
  `users`), igual que en el estado actual.
- Sin resolver: cómo migran las filas hoy existentes de `users` (rol `ANALISTA_SINIESTROS`/
  `REFERENTE_ASEGURADORA`, sin aseguradora) a este modelo. No asumir un mecanismo — es un problema
  de migración que el equipo no cerró todavía (mismo gap ya señalado en memoria de sesiones previas
  sobre H0002/H0003).

### Expediente, clasificación y scoring — evolución objetivo de `cases-service` / `classification-service`

```mermaid
erDiagram
    ESTADO_EXPEDIENTE ||--o{ EXPEDIENTE : "estado_actual_id"
    ESTADO_EXPEDIENTE ||--o{ HISTORIAL_ESTADO_EXPEDIENTE : "estado_inicial_id"
    ESTADO_EXPEDIENTE ||--o{ HISTORIAL_ESTADO_EXPEDIENTE : "estado_final_id"
    EXPEDIENTE ||--o| CLASIFICACION : "decisión de (expediente.clasificacion_id, nullable)"
    EXPEDIENTE ||--o{ DOCUMENTO_EXPEDIENTE : "acumula adjuntos"
    EXPEDIENTE ||--o{ HISTORIAL_ESTADO_EXPEDIENTE : "traza transiciones"

    ESTADO_EXPEDIENTE {
        bigint id PK
        varchar nombre
        varchar descripcion
        varchar estado_asegurado "label agregado que ve el asegurado — varios estados internos pueden mapear al mismo, ej. RECIBIDO"
        boolean es_final
        int plazo_maximo_dias "nullable — agregado, ver 'Decisiones tomadas' arriba (H0015)"
    }

    EXPEDIENTE {
        bigint id PK
        varchar poliza_id "referencia externa a BD Aseguradora, no FK local"
        timestamp fecha_hora_siniestro
        varchar ubicacion_siniestro
        bigint estado_actual_id FK
        bigint clasificacion_id FK "nullable hasta que el analista decide"
        boolean fue_fast_track
        text descripcion
        timestamptz fecha_hora_creacion
        timestamptz fecha_hora_ultima_actualizacion
        varchar actualizado_por
        varchar creado_por
        varchar asegurado_id "referencia externa, no FK local"
        bigint aseguradora_id "referencia lógica — vive en auth/rules, no @ManyToOne"
        bigint analista_id "referencia lógica a ANALISTA_SEGURO (otro módulo)"
        numeric puntaje_riesgo "nullable"
        varchar banda_riesgo "enum RiskBand, nullable"
        text nota_ajuste_manual "nullable"
        bigint configuracion_scoring_id "referencia lógica a rules-service"
        bigint hecho_generador_id "referencia lógica a rules-service"
        bigint cobertura_id "referencia lógica a rules-service"
    }

    CLASIFICACION {
        bigint id PK
        bigint expediente_id FK
        varchar decision "APROBAR | RECHAZAR"
        text justificacion_analista "nullable — obligatoria solo si difiere de la recomendación del LLM (H0014)"
        timestamptz fecha_subida
    }

    DOCUMENTO_EXPEDIENTE {
        bigint id PK
        bigint expediente_id FK
        varchar tipo
        varchar nombre_archivo
        varchar tipo_contenido
        bytea contenido
        timestamptz fecha_hora_subida
    }

    HISTORIAL_ESTADO_EXPEDIENTE {
        bigint id PK
        bigint expediente_id FK
        bigint estado_inicial_id FK "nullable — null = alta del expediente"
        bigint estado_final_id FK
        varchar cambiado_por
        varchar razon
        timestamptz fecha_cambio
        text observacion "nullable"
    }
```

```mermaid
erDiagram
    ANALISIS_LLM ||--o{ RAZON_LLM : "fundamenta con"

    ANALISIS_LLM {
        bigint id PK
        bigint expediente_id "referencia lógica a EXPEDIENTE (otro módulo) — no FK, igual que hoy classification_log.case_id"
        varchar recomendacion "enum Classification"
        varchar modelo "nullable — null cuando fue Fast Track por reglas"
        varchar version_prompt "nullable"
        numeric confianza
        bigint latencia_llm_ms
    }

    RAZON_LLM {
        bigint id PK
        varchar razon
        bigint analisis_id FK
    }
```

**Notas:**
- `estado_expediente` reemplaza el enum fijo `CaseStatus` de hoy por un catálogo configurable —
  coherente con H0015 ("el referente puede definir los estados activos del flujo"). Los diagramas de
  estado de `Arbiter Documentación` (`Diagramas de Estado de Expedientes`) usan hoy en la práctica un
  modelo más chico que la lista NSIN001 completa de la Historia H0006/H0010 (`PENDIENTE_CLASIFICACION`,
  `PENDIENTE_CLASIFICACION_MANUAL`, `REQUIERE_REVISION`, `APROBADO`, `RECHAZADO` a nivel interno; el
  asegurado ve un estado agregado tipo `RECIBIDO`/`APROBADO`/`RECHAZADO`) — de ahí sale
  `estado_asegurado` como columna separada de `nombre`.
- `clasificacion` es la **decisión final del analista** sobre el expediente (uno por expediente, vía
  `expediente.clasificacion_id`) — distinta de `analisis_llm`, que es cada corrida individual del
  modelo (puede haber varias por expediente, igual que `classification_attempts` cuenta hoy en
  `CASES`). Esto separa, más de lo que separa `classification_log` hoy, la sugerencia del modelo de
  la decisión humana (alineado con H0014: "la recomendación siempre se presenta como sugerencia; el
  Analista decide y registra su resolución").
- **Cambio de diseño respecto a hoy:** `puntaje_riesgo`/`banda_riesgo`/`nota_ajuste_manual` quedan
  como atributos directos de `EXPEDIENTE`, sin un snapshot inmutable por corrida en `ANALISIS_LLM`
  (a diferencia de `classification_log.risk_score` hoy, que es el snapshot autoritativo). Si el
  score se recalcula con cada documento nuevo (H0012: "el score se recalcula automáticamente si se
  agrega nueva documentación"), esto implica que el histórico de scores por corrida se pierde salvo
  que se reconstruya desde `historial_estado_expediente`/`analisis_llm`. Vale la pena confirmarlo
  con el equipo antes de implementar — no se decidió acá, solo se documenta la diferencia.
- `razon_llm` normaliza en filas lo que hoy es el JSON `factors` de `classification_log` — mismo
  contenido, tabla propia en lugar de columna JSON.
- No incluye `Notificación` (H0016/H0017, épica 8) ni nada de `reports-service` (H0018-H0020, épica
  9) — son historias de release R3/R4, todavía no llegó su turno de diseño de datos. No inventar
  columnas para ellas todavía.

---

## Cómo mantener esto al día

Cuando se agregue una `@Entity` nueva (por ejemplo al arrancar `rules-service`, o al tocar
`auth-service`):
1. Sumarla al bloque `erDiagram` de su módulo, en la sección "Estado actual" (o crear un bloque
   nuevo si es un módulo que hoy no tiene ninguno acá) — y sacarla del "Modelo objetivo" si ya
   estaba ahí, para no tener la misma entidad documentada dos veces.
2. Si tiene una relación **dentro del mismo módulo**, dibujarla como FK real (`||--o{`).
3. Si referencia una entidad de **otro módulo**, documentarla como referencia lógica (como
   `classification_log.case_id` arriba) — nunca como una FK cruzada, porque no existe tal cosa en
   esta arquitectura.
4. Contrastar contra `db/init.sql` (o la migración que corresponda cuando se decida Flyway/Liquibase,
   ver `CLAUDE.md`) para que los tipos y nullability no se desincronicen del DDL real.
5. Si al implementar una entidad del "Modelo objetivo" aparece una decisión distinta a la que quedó
   documentada acá (nombres de columna, nullability, el conflicto de multi-tenant sin cerrar), no
   pisarla en silencio — dejar registrado el cambio y por qué, mismo criterio que ya se usa para el
   resto del documento.
