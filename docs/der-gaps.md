# Baches del DER

Lo que falta editar en el DER (`docs/arbiter der.mdj`) para que coincida con lo implementado
(`db/init-multitenant.sql`). **No son historias de desarrollo**: son correcciones al propio DER, y
quedan afuera del backlog de Trello por eso.

El DER sigue siendo la fuente de verdad (`CLAUDE.md`). Este documento no propone desvíos: es la
lista de qué hay que corregirle. Cuando una entrada queda aplicada en el `.mdj`, se borra de acá.

**Revisado el 11/10/2026, séptima pasada** contra el `.mdj` (última edición: 07/10) y contra
`db/init-multitenant.sql`. Salieron las entradas 1, 2 y 3, que ya están en el diagrama. Las demás
conservan su número.

**Dos criterios que valen para todas las entradas:**

- **UNIQUE en una columna FK:** no se marca. La cardinalidad 1:1 va en la relación del diagrama (el
  extremo con `1` en vez de `0..*`).
- **UNIQUE compuesta (dos o más columnas):** StarUML no dejó modelarla, así que no se marca en
  ninguna columna. Si la entidad tiene una, va una nota de texto al lado.

---

## Para editar en el `.mdj`

### 4 · `peritaje` — nota de texto con la cardinalidad real

Un expediente puede tener hasta dos filas: una por perito (`ESTUDIO_LIQUIDADOR`) y otra por servicio
técnico (`SERVICIO_TECNICO`). La UNIQUE real es `(expediente_id, tipo_proveedor)`.

**Acción:** agregar una nota de texto junto a la entidad: la cardinalidad es
`expediente 0..1—0..2`, no `1—1`. Hoy el `.mdj` no tiene ninguna nota.

### 5 y 6 · `analisis_documento` — dos columnas que faltan

| Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|
| `hecho_generador_descripto` (`described_claim_cause`) | VARCHAR(120) | sí | ninguna — **sin FK** a `hecho_generador`, a propósito: es registro de auditoría |
| `estado_extraccion` (`extraction_status`) | VARCHAR(20) | no | default `'COMPLETE'`; CHECK `IN ('COMPLETE', 'PARTIAL', 'FAILED')` |

**Acción:** agregar las dos columnas.

### 7 · `regla_aseguradora.creado_por` y `configuracion_scoring.creado_por` — faltan

| Tabla | Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `regla_aseguradora` | `creado_por` (`created_by`) | BIGINT | no | FK → `usuario.id` |
| `configuracion_scoring` | `creado_por` (`created_by`) | BIGINT | no | FK → `usuario.id` |

Apuntan a `usuario` y no a `referente_aseguradora` a propósito.

**Acción:** agregar las dos columnas como FK obligatoria a `usuario`.

### 8 · `cobertura.base_franquicia` y `liquidacion.base_franquicia` — faltan

| Tabla | Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `cobertura` | `base_franquicia` (`deductible_basis`) | VARCHAR(20) | no | default `'SUM_INSURED'`; CHECK `IN ('SUM_INSURED', 'LOSS_AMOUNT')` |
| `liquidacion` | `base_franquicia` (`deductible_basis`) | VARCHAR(20) | no | default `'SUM_INSURED'`; mismo CHECK |

**Acción:** agregar la columna a las dos entidades.

### 9 · `expediente.fue_fast_track` → `expediente.clasificacion_por_reglas`

| Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|
| ~~`fue_fast_track`~~ (`was_fast_track`) | BOOLEAN | — | ya no existe |
| `clasificacion_por_reglas` (`rules_classification`) | VARCHAR(50) | sí | CHECK `IN ('FAST_TRACK', 'FALTA_DOCUMENTACION', 'LLM_SOLICITA_REVISION_MANUAL', 'LLM_NO_RECOMIENDA_APROBAR')`; nula cuando decidió el modelo |

**Acción:** reemplazar una columna por la otra.

### 10 · `perito` y `peritaje` — renombre y ramos N:M

| Antes | Después | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `perito` | `proveedor_externo` (`service_provider`) | — | — | entidad renombrada |
| `perito.rama_id` | — | — | — | se saca: pasa a `proveedor_externo_rama` |
| — | `proveedor_externo_rama.proveedor_externo_id` (`service_provider_id`) | BIGINT | no | PK compuesta; FK → `proveedor_externo.id` |
| — | `proveedor_externo_rama.rama_id` (`branch_id`) | BIGINT | no | PK compuesta; FK → `rama.id` |
| `peritaje` | `derivacion_expediente` (`case_referral`) | — | — | entidad renombrada |
| `peritaje.nombre_perito` | `derivacion_expediente.nombre_proveedor` (`provider_name`) | VARCHAR(120) | no | — |
| `peritaje.email_perito` | `derivacion_expediente.email_proveedor` (`provider_email`) | VARCHAR(150) | no | — |
| `peritaje.perito_id` | `derivacion_expediente.proveedor_id` (`provider_id`) | BIGINT | sí | FK → `proveedor_externo.id` |
| `antecedente_fraude.peritaje_id` | `antecedente_fraude.derivacion_expediente_id` (`case_referral_id`) | BIGINT | sí | FK → `derivacion_expediente.id` |

Un proveedor sin filas en `proveedor_externo_rama` cubre todos los ramos.

**Acción:** renombrar las dos entidades y sus columnas, sacar `rama_id` de `proveedor_externo` y
agregar `proveedor_externo_rama` como N:M entre `proveedor_externo` y `rama`. La nota de la entrada
4 va junto a `derivacion_expediente`.

### 11 · `rama` y `hecho_generador` — pasan a ser de cada aseguradora

| Tabla | Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `rama` | `nombre_externo` (`external_name`) | VARCHAR(100) | sí | UNIQUE; columna nueva |

`nombre_externo` es como la BD de la aseguradora llama al ramo. Las relaciones no cambian: cambia de
qué lado están las entidades.

**Acción:** mover `rama` y `hecho_generador` de la parte común a la parte por aseguradora y agregar
`rama.nombre_externo`.

### 12 · `hecho_generador.nombre` — sacarle la UNIQUE

En el DER está marcada única sola. En el esquema la unicidad es `(rama_id, nombre)`: "Robo en vía
pública" y "Hurto" existen una vez por ramo.

**Acción:** sacar la marca `unique` de `hecho_generador.nombre` y dejar una nota de texto: un nombre
no se repite dentro de un ramo, pero sí entre ramos.

### 13 · `analisis_llm.fecha_analisis` — falta

| Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|
| `fecha_analisis` (`analyzed_at`) | TIMESTAMPTZ | no | default `NOW()` |

**Acción:** agregar la columna. Es la marca temporal que pide la auditoría de clasificaciones.

### 15 · Marcas de FK que faltan

| Tabla | Columna | Apunta a |
|---|---|---|
| `hecho_generador` | `rama_id` | `rama.id` |
| `antecedente_fraude` | `analista_declarante_id` | `analista_siniestro.id` |
| `antecedente_fraude` | `expediente_id` | `expediente.id` |

**Acción:** marcar las tres como FK.

### 16 · Tipos, largos y nombres que no coinciden con el esquema

| Tabla | Columna | En el DER | En el esquema |
|---|---|---|---|
| `asegurado` | `dni` | VARCHAR(8) | VARCHAR(20) |
| `analisis_llm` | `recomendacion` | VARCHAR(20) | VARCHAR(50) |
| `analisis_llm` | `confianza` | NUMERIC(5,2) | NUMERIC(5,3) |
| `analisis_llm` | `hecho_generador_sugerido` | VARCHAR(100) | VARCHAR(120) |
| `analisis_llm` | `evidencia_hecho_generador` | VARCHAR(255) | TEXT |
| `analisis_imagen` | `modelo_utlizado` | VARCHAR(255) | VARCHAR(80) |
| `antecedente_fraude` | `motivo` | VARCHAR(20) | TEXT |
| `documento_expediente` | `tipo` | VARCHAR(60) | VARCHAR(255) |
| `documento_expediente` | `tipo_contenido` | MIME | VARCHAR(255) |
| `poliza` | `producto` | VARCHAR(100) | VARCHAR(1000) |
| `requisito_documental` | `tipo_documento` | VARCHAR(20) | VARCHAR(50) |
| `resultado_regla` | `tipo_regla` | VARCHAR(20) | VARCHAR(40) |

Dos nombres con error de tipeo: `analisis_imagen.modelo_utlizado` (por `modelo_utilizado`) y
`poliza_consultada.canitdad_siniestros_previos` (por `cantidad_siniestros_previos`).

**Acción:** corregir tipos, largos y los dos nombres.

### 17 · UNIQUE de una sola columna que faltan

| Tabla | Columna |
|---|---|
| `rol` | `codigo` |
| `permiso` | `nombre` |
| `estado_expediente` | `nombre` |
| `asegurado` | `dni` |
| `poliza` | `nro_poliza_externo` |

**Acción:** marcar las cinco como `unique`. Ninguna es FK.

### 18 · `cobertura.nombre_externo` — falta, y `cobertura.nombre` deja de ser única sola

| Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|
| `nombre` (`name`) | VARCHAR(100) | no | UNIQUE junto con `rama_id`; hoy el DER la marca única sola |
| `nombre_externo` (`external_name`) | VARCHAR(160) | sí | UNIQUE junto con `rama_id`; columna nueva |

`nombre_externo` es como la BD de la aseguradora llama a la cobertura, igual que
`rama.nombre_externo` (entrada 11). Las dos UNIQUE son por ramo porque esa base repite un mismo
nombre entre ramos.

**Acción:** agregar `cobertura.nombre_externo`, sacar la marca `unique` de `cobertura.nombre` y
dejar una nota de texto: ninguno de los dos nombres se repite dentro de un ramo, pero sí entre ramos.

### 20 · `metrica` → `metrica_dia` y las tres `metrica_diaria_*`

La entidad `metrica` del DER no existe en el esquema. Lo implementado son cuatro tablas por
aseguradora que guardan los totales de los días ya cerrados.

| Tabla | Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `metrica_dia` (`metrics_day`) | `dia` (`day`) | DATE | no | PK |
| `metrica_dia` | `fecha_calculo` (`computed_at`) | TIMESTAMPTZ | no | default `NOW()` |
| `metrica_diaria_ingreso` (`metrics_daily_intake`) | `dia` (`day`) | DATE | no | FK → `metrica_dia.dia` |
| `metrica_diaria_ingreso` | `hecho_generador_id` (`claim_cause_id`) | BIGINT | no | FK → `hecho_generador.id` |
| `metrica_diaria_ingreso` | `analista_id` (`analyst_id`) | BIGINT | sí | sin FK |
| `metrica_diaria_ingreso` | `denunciados` (`reported`), `fast_track` | BIGINT | no | — |
| `metrica_diaria_resolucion` (`metrics_daily_resolution`) | `dia`, `hecho_generador_id`, `analista_id` | — | — | igual que en `metrica_diaria_ingreso` |
| `metrica_diaria_resolucion` | `estado_final` (`final_status`) | VARCHAR(60) | no | — |
| `metrica_diaria_resolucion` | `resueltos`, `fast_track`, `en_plazo`, `reabiertos`, `acuerdo_evaluables`, `acuerdo_coincidentes`, `fraude_determinado`, `fraude_respaldado_por_perito` | BIGINT | no | — |
| `metrica_diaria_resolucion` | `segundos_totales`, `segundos_en_espera`, `segundos_fast_track` | DOUBLE PRECISION | no | — |
| `metrica_diaria_resolucion` | `monto_reclamado_fraude` (`fraud_claimed_amount`) | NUMERIC(38,2) | no | — |
| `metrica_diaria_liquidacion` (`metrics_daily_settlement`) | `dia`, `hecho_generador_id`, `analista_id` | — | — | igual que en `metrica_diaria_ingreso` |
| `metrica_diaria_liquidacion` | `liquidaciones` (`settlements`), `expedientes_con_reclamo` (`claimed_cases`) | BIGINT | no | — |
| `metrica_diaria_liquidacion` | `monto_liquidado`, `monto_reclamado`, `monto_franquicia`, `monto_cuotas`, `monto_saldo_deuda` | NUMERIC(38,2) | no | — |

Las tres `metrica_diaria_*` no tienen `id`: son una fila por día, hecho generador y analista.

**Acción:** sacar `metrica` y agregar las cuatro tablas, con `metrica_dia` 1—N contra cada
`metrica_diaria_*` y `hecho_generador` 1—N contra cada una.

### 21 · `atributo_documento` — tabla nueva

| Tabla | Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `atributo_documento` (`document_attribute`) | `id` | BIGINT | no | PK |
| `atributo_documento` | `nombre` (`name`) | VARCHAR(60) | no | |
| `atributo_documento` | `valor` (`value`) | TEXT | no | |
| `atributo_documento` | `analisis_id` (`analysis_id`) | BIGINT | no | FK → `analisis_documento.id` |

Un `analisis_documento` tiene de 0 a N `atributo_documento`: una fila por dato del bien que dice el
documento (`marca`, `modelo`, `IMEI`).

**Acción:** agregar la entidad con su relación a `analisis_documento`, y sacar del diagrama las
columnas `marca`, `modelo` e `imei` de `analisis_documento`: se borraron de la base el 12/10 con
`2026-10-12-borrar-columnas-de-documento.sql`.

### 22 · `estado_expediente.codigo_estado_asegurado` — columna nueva

| Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|
| `codigo_estado_asegurado` (`insured_status_code`) | VARCHAR(40) | no | uno de los valores de `InsuredStatus` |

Es el código que recibe el asegurado en lugar del nombre interno del estado; `estado_asegurado`
(`insured_status`) queda como su etiqueta. Migración: `2026-10-11-codigo-del-estado-del-asegurado.sql`.

**Acción:** agregarla al DER.

### 23 · `requisito_documental.obligatorio` — sobra

La columna se borró del esquema (`2026-10-13-borrar-obligatorio-de-la-agenda.sql`): siempre valió
`TRUE` y ninguna lectura la miraba. Lo que una denuncia puede presentar con menos documentos es la
lista de Fast Track, que vive en la configuración de esa regla.

**Acción:** dropear `obligatorio` de `requisito_documental`.

### 25 · `configuracion_banda.activo` — sobra

La columna se borró del esquema (`2026-10-13-borrar-activo-de-banda-de-puntaje.sql`): las bandas se
borran y se vuelven a escribir en cada guardado del puntaje, siempre en `TRUE`, y ninguna lectura
filtraba por ella. Apagar el puntaje entero es `configuracion_scoring.activa`, que queda.

**Acción:** dropear `activo` de `configuracion_banda`.

---

## Decisiones pendientes antes de tocar el `.mdj`

### 24 · `requisito_documental.banda_riesgo` — está en el esquema y nadie la usa

Siempre vale `NULL` y ninguna lectura la mira. Exigir un documento según la banda tiene problemas de
fondo: la banda recién existe después de clasificar, y pedirle un papel más solo al asegurado
marcado como riesgoso le revela esa marca.

**Decisión:** implementarla o dropearla del esquema y del DER. Pendiente de discutir con el equipo.

### 13 · `expediente.destino` — está en el esquema y no en el DER

| Tabla | Columna | Tipo de dato | Nulo | Por qué no se suma directo |
|---|---|---|---|---|
| `expediente` | `destino` (`destination`) | VARCHAR(40) | sí | nada la escribe; sus valores nunca se definieron |

**Decisión:** sumarla al DER con una semántica definida, o dropearla del esquema y de la entidad.

`cobertura.es_individual`, que compartía esta entrada, se borró del esquema
(`2026-10-13-borrar-es-individual-de-cobertura.sql`): repetía `cubre_grupo_familiar` y nada la leía.
Nunca estuvo en el DER, así que no hay nada que editar en el `.mdj`.

### 14 · `aseguradora.logo` — está en el DER y no en el esquema

| Columna | Tipo de dato | En el esquema |
|---|---|---|
| `logo` | BYTEA | no existe |

**Decisión:** sacarla del DER, o dejarla con una nota que diga que todavía no está implementada.

### 19 · `atributo_bien` — tabla nueva en la BD de la aseguradora

| Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|
| `id` | BIGINT | no | PK |
| `poliza_id` | BIGINT | no | FK a `poliza` de la BD de la aseguradora |
| `nombre` | VARCHAR(60) | no | UNIQUE junto con `poliza_id` |
| `valor` | VARCHAR(255) | no | |

Vive en los esquemas `aseguradora_*`, no en los de Arbiter, y hoy el `.mdj` no tiene ninguna tabla de
esa base.

**Decisión:** sumarla solo si el diagrama pasa a incluir las tablas de la BD de la aseguradora.

---

## Plantilla para la próxima entrada

```
### N · `tabla.columna` — descripción corta del bache

| Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|
| ... | ... | ... | ... |

**Acción:** agregar / dropear / corregir tipo / decisión pendiente (con quién hay que confirmarla).
```
