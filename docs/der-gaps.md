# Baches del DER

Lo que falta editar en el DER (`docs/arbiter der.mdj`) para que coincida con lo implementado
(`db/init-multitenant.sql`). **No son historias de desarrollo**: son correcciones al propio DER, y
quedan afuera del backlog de Trello por eso.

El DER sigue siendo la fuente de verdad (`CLAUDE.md`). Este documento no propone desvíos: es la
lista de qué hay que corregirle. Cuando una entrada queda aplicada en el `.mdj`, se borra de acá.

**Revisado el 16/10/2026, novena pasada** contra el `.mdj` editado ese día y contra
`db/init-multitenant.sql`. Salieron las entradas 5, 6, 8, 9, 13 (`analisis_llm.fecha_analisis`), 16,
17, 22, 23, 25, 26, 27 y 32, que ya están en el diagrama. Las entradas 7, 10, 11, 12, 15, 18, 21 y 31
quedaron a medias y dicen solo lo que falta. Se sumó la 35. Las demás conservan su número.

**Décima pasada, el mismo 16/10:** se cruzó cada columna marcada como FK contra las líneas del
diagrama, y cada línea contra el esquema. Salieron las entradas 36, 37 y 38, y se corrigieron la 10
y la 15.

**Criterios que valen para todas las entradas:**

- **UNIQUE en una columna FK:** no se marca. La cardinalidad 1:1 va en la relación del diagrama (el
  extremo con `1` en vez de `0..*`).
- **UNIQUE compuesta (dos o más columnas):** StarUML no dejó modelarla, así que no se marca en
  ninguna columna. Si la entidad tiene una, va una nota de texto al lado.
- **Esquema común o por aseguradora:** se distingue por el borde de la entidad. Las del esquema
  común (`arbiter_common`) llevan borde azul; las de cada aseguradora, el borde por defecto.
- **FK:** se tilda `Foreign Key` en la columna y se dibuja la relación. `Reference To` queda vacío,
  como en el resto del diagrama.

---

## Para editar en el `.mdj`

### 4 · `derivacion_expediente` — nota de texto con la cardinalidad real

Un expediente puede tener hasta dos filas: una por perito (`ESTUDIO_LIQUIDADOR`) y otra por servicio
técnico (`SERVICIO_TECNICO`). La UNIQUE real es `(expediente_id, tipo_proveedor)`.

**Acción:** agregar una nota de texto junto a la entidad: la cardinalidad es
`expediente 0..1—0..2`, no `1—1`.

### 7 · `configuracion_scoring.creado_por` — falta la relación

La columna ya está como FK obligatoria a `usuario`. La de `regla_aseguradora` ya tiene su relación.

**Acción:** dibujar la relación `usuario` 1—0..\* `configuracion_scoring`.

### 10 · `proveedor_externo_rama` — falta la tabla N:M

Los renombres de `perito` y `peritaje` ya están en el diagrama, y `proveedor_externo` ya no tiene
`rama_id`. Falta la tabla que dice qué ramos cubre cada proveedor:

| Tabla | Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `proveedor_externo_rama` (`service_provider_branch`) | `proveedor_externo_id` (`service_provider_id`) | BIGINT | no | PK compuesta; FK → `proveedor_externo.id` |
| `proveedor_externo_rama` | `rama_id` (`branch_id`) | BIGINT | no | PK compuesta; FK → `rama.id` |

Un proveedor cubre solo los ramos con fila en `proveedor_externo_rama`, y tiene al menos una.

**Acción:**

- Agregar `proveedor_externo_rama` como N:M entre `proveedor_externo` y `rama`.
- Borrar del modelo la relación `rama` — `proveedor_externo`: quedó de cuando el proveedor tenía
  `rama_id`. Ya no está dibujada, solo aparece en el explorador del modelo (ver la entrada 37).
- En la relación `derivacion_expediente` — `antecedente_fraude`, poner `0..1` del lado de
  `antecedente_fraude`. Hoy no tiene cardinalidad en ningún extremo.

### 11 · `rama` y `hecho_generador` — pasan a ser de cada aseguradora

`rama.nombre_externo` ya está en el diagrama. Las dos entidades siguen con el borde azul del esquema
común.

**Acción:** pasar `rama` y `hecho_generador` al borde por defecto. `rama` aparece dos veces en el
diagrama.

### 12 · `hecho_generador.nombre` — falta la nota

La marca `unique` ya no está. En el esquema la unicidad es `(rama_id, nombre)`: "Robo en vía
pública" y "Hurto" existen una vez por ramo.

**Acción:** dejar una nota de texto: un nombre no se repite dentro de un ramo, pero sí entre ramos.

### 15 · `antecedente_fraude.analista_declarante_id` — falta la relación

Las tres columnas ya están marcadas como FK. Las relaciones con `derivacion_expediente` y con
`expediente` ya están. La tabla no tiene `rama_id`.

**Acción:** dibujar la relación `analista_siniestro` 1—0..\* `antecedente_fraude`.

### 18 · `cobertura` — falta la nota

`cobertura.nombre_externo` ya está y `cobertura.nombre` ya no es única sola. En el esquema, los dos
nombres son únicos junto con `rama_id`: la BD de la aseguradora repite un mismo nombre entre ramos.

**Acción:** dejar una nota de texto: ninguno de los dos nombres se repite dentro de un ramo, pero sí
entre ramos.

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
documento (`marca`, `modelo`, `IMEI`). Las columnas `marca`, `modelo` e `imei` ya salieron de
`analisis_documento`.

**Acción:** agregar la entidad con su relación a `analisis_documento`.

### 28 · `shedlock` — tabla nueva en el esquema común

| Tabla | Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `shedlock` | `name` | VARCHAR(64) | no | PK |
| `shedlock` | `lock_until` | TIMESTAMP | no | |
| `shedlock` | `locked_at` | TIMESTAMP | no | |
| `shedlock` | `locked_by` | VARCHAR(255) | no | |

No es dato de negocio: una fila por barrido programado de `cases-service`, para que con varias
instancias lo corra una sola a la vez. La tabla y las columnas llevan los nombres de la librería
(ShedLock), por eso quedan en inglés. Migración: `2026-10-12-lock-de-barridos.sql`.

**Acción:** agregarla con borde azul (esquema común), como entidad suelta, sin relaciones.

### 29, 30 y 31 · Tipos de documento en la base, por ramo

Migración: `2026-10-14-tipos-de-documento.sql`. Los tipos de documento dejan de estar fijos en el
front.

**29 · `tipo_documento` (`arbiter_common.document_type`) — tabla nueva del esquema común**

| Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|
| `id` | BIGINT | no | PK |
| `codigo` (`code`) | VARCHAR(50) | no | UNIQUE |
| `nombre` (`name`) | VARCHAR(100) | no | — |

**30 · `ramo_tipo_documento` (`branch_document_type`) — tabla nueva por aseguradora**

| Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|
| `id` | BIGINT | no | PK |
| `rama_id` (`branch_id`) | BIGINT | no | FK → `rama.id`, ON DELETE CASCADE |
| `tipo_documento_id` (`document_type_id`) | BIGINT | no | FK → `tipo_documento.id` |

UNIQUE compuesta `(rama_id, tipo_documento_id)`: va como nota de texto.

**31 · `requisito_documental.tipo_documento_id` — falta la relación y la nota**

La columna ya reemplazó a `tipo_documento` VARCHAR y está marcada como FK. Tiene además una FK
compuesta `(rama_id, tipo_documento_id)` → `ramo_tipo_documento`, que impide que la agenda de un ramo
pida un documento que no es suyo: va como nota de texto.

**Acción:**

- Agregar `tipo_documento` con borde azul y `ramo_tipo_documento` con borde por defecto, con sus
  relaciones: `rama` 1—0..\* `ramo_tipo_documento` 0..\*—1 `tipo_documento`.
- Dibujar la relación `tipo_documento` 1—0..\* `requisito_documental`.
- Agregar las dos notas: la UNIQUE de `ramo_tipo_documento` y la FK compuesta de
  `requisito_documental`.

### 33 · `paso_puesta_en_marcha` y `reinicio_puesta_en_marcha` — tablas nuevas por aseguradora

Migración: `2026-10-12-puesta-en-marcha.sql`. Son de la guía de primera carga del referente. El
estado de cada paso sale de la configuración; estas tablas guardan solo lo que la configuración no
dice: los pasos que la aseguradora marcó como que no usa, el último guardado de cada paso y cuándo
se reinició la guía.

| Tabla | Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `paso_puesta_en_marcha` (`onboarding_step`) | `clave_paso` (`step_key`) | VARCHAR(40) | no | PK |
| `paso_puesta_en_marcha` | `omitido` (`skipped`) | BOOLEAN | no | default `FALSE` |
| `paso_puesta_en_marcha` | `fecha_guardado` (`touched_at`) | TIMESTAMPTZ | sí | — |
| `reinicio_puesta_en_marcha` (`onboarding_reset`) | `id` | SMALLINT | no | PK; default `1`; CHECK `id = 1` |
| `reinicio_puesta_en_marcha` | `fecha_reinicio` (`reset_at`) | TIMESTAMPTZ | no | — |
| `reinicio_puesta_en_marcha` | `reiniciado_por` (`reset_by`) | BIGINT | sí | FK → `usuario.id` |

`clave_paso` es el nombre del paso, más `:<rama_id>` en el paso de coberturas, que es por ramo.
`reinicio_puesta_en_marcha` tiene una sola fila.

**Acción:** agregar las dos entidades con borde por defecto (por aseguradora).
`reinicio_puesta_en_marcha` va con su relación a `usuario`; `paso_puesta_en_marcha` queda suelta.

### 34 · Notas de UNIQUE compuesta que faltan

| Entidad | UNIQUE del esquema |
|---|---|
| `cobertura_contratada` | `(poliza_id, cobertura_id)` |
| `documento_expediente` | `(expediente_id, tipo)` |

Las demás UNIQUE compuestas ya tienen su entrada: 4, 12, 18 y 30.

**Acción:** agregar una nota de texto al lado de cada entidad. Por ejemplo, para
`documento_expediente`: "Un expediente tiene un solo documento de cada tipo".

### 35 · Dos relaciones que se perdieron al editar el 16/10

Al editar el DER el 16/10, dos líneas que ya existían quedaron enganchadas a otras entidades. Las
columnas siguen marcadas como FK, pero se quedaron sin su línea:

| Relación que falta | Columna FK | Adónde fue la línea |
|---|---|---|
| `usuario` 1—0..\* `referente_aseguradora` | `referente_aseguradora.usuario_id` | a `usuario` — `regla_aseguradora` (entrada 7) |
| `poliza_consultada` — `liquidacion` | `liquidacion.poliza_consultada_id` | a `antecedente_fraude` — `derivacion_expediente` (entrada 10) |

**Acción:** volver a dibujar las dos. Las relaciones `usuario` — `regla_aseguradora` y
`antecedente_fraude` — `derivacion_expediente` se quedan: también hacían falta.

### 36 · Columnas FK sin su línea

Cada columna de esta tabla está marcada como FK en el `.mdj` y existe en el esquema, pero no tiene
una relación dibujada hacia su tabla. No incluye las que ya tienen entrada (7, 15, 31 y 35).

| Relación que falta | Columna FK |
|---|---|
| `estado_expediente` 1—0..\* `expediente` | `expediente.estado_actual_id` |
| `hecho_generador` 1—0..\* `expediente` | `expediente.hecho_generador_id` |
| `cobertura` 1—0..\* `expediente` | `expediente.cobertura_id` |
| `usuario` 0..1—0..\* `historial_estado_expediente` | `historial_estado_expediente.cambiado_por` |
| `documento_expediente` 0..1—0..\* `analisis_imagen` | `analisis_imagen.documento_similar_id` (la segunda línea entre las dos; la de `documento_expediente_id` ya está) |
| `usuario` 1—0..\* `mensaje_expediente` | `mensaje_expediente.emisor_id` |
| `expediente` 1—0..2 `derivacion_expediente` | `derivacion_expediente.expediente_id` (ver la entrada 4) |
| `analista_siniestro` 1—0..\* `derivacion_expediente` | `derivacion_expediente.derivado_por` |
| `documento_expediente` 0..1—0..\* `derivacion_expediente` | `derivacion_expediente.documento_informe_id` |
| `usuario` 1—0..\* `asegurado` | `asegurado.usuario_id` |

La de `asegurado` existe en el modelo pero no en el diagrama: se puede arrastrar desde el explorador
del modelo en vez de dibujarla de nuevo.

**Acción:** dibujar las diez.

### 37 · Relaciones de más

**Una repetida en el diagrama.** Entre `expediente` y `historial_estado_expediente` hay dos líneas, y
el esquema tiene una sola FK (`expediente_id`). Una va `1—0..*`; la otra no tiene cardinalidad.

**Catorce que quedaron solo en el modelo.** No se ven en el diagrama, pero siguen en el explorador
del modelo y en el `.mdj`. Son restos de ediciones anteriores:

| Relación | Cuántas | Por qué sobra |
|---|---|---|
| `asegurado` — `expediente` | 1 | repetida: la dibujada se queda |
| `expediente` — `poliza` | 1 | repetida: la dibujada se queda |
| `expediente` — `poliza_consultada` | 2 | repetidas: la dibujada se queda |
| `regla_aseguradora` — `resultado_regla` | 1 | repetida: la dibujada se queda |
| `cobertura` — `cobertura_contratada` | 3 | repetidas: la dibujada se queda |
| `cobertura` — `poliza` | 1 | `poliza` no tiene FK a `cobertura`: van por `cobertura_contratada` |
| `historial_regla_aseguradora` — `resultado_regla` | 1 | `resultado_regla` no tiene FK al historial |
| `rama` — `proveedor_externo` | 1 | ver la entrada 10 |
| `aseguradora` — `regla_aseguradora`, `referente_aseguradora` y `expediente` | 3 | ninguna de las tres tiene FK a `aseguradora`: la aseguradora es el esquema |

**Acción:**

- Borrar la línea sin cardinalidad entre `expediente` y `historial_estado_expediente`.
- Borrar del modelo las catorce. En StarUML: clic derecho en el explorador del modelo → "Delete from
  Model". Borrarlas del diagrama no alcanza.

### 38 · Cardinalidades que el esquema contradice

| Relación | En el `.mdj` | Debería ser | Por qué |
|---|---|---|---|
| `expediente` — `antecedente_fraude` | `1—1` | `1—0..1` | UNIQUE en `antecedente_fraude.expediente_id`, y la mayoría de los expedientes no tiene antecedente |
| `expediente` — `liquidacion` | `1—1` | `1—0..1` | UNIQUE en `liquidacion.expediente_id`, y solo se liquida al aprobar |
| `documento_expediente` — `analisis_documento` | `1—0..*` | `1—0..1` | UNIQUE en `analisis_documento.documento_expediente_id` |

En el `.mdj`, un extremo sin cardinalidad cuenta como `1`.

**Acción:** corregir los tres extremos.

---

## Decisiones pendientes antes de tocar el `.mdj`

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
