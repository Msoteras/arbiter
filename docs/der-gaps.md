# Baches del DER

Registro de lugares donde el DER (`docs/arbiter der.mdj`) tiene una columna, una relación o un dato
que no se sostiene contra lo implementado — redundante, sin semántica definida, o de un modelo
viejo que quedó atrás. **No son historias de desarrollo**: son correcciones al propio DER, y quedan
afuera del backlog de Trello por eso.

El DER sigue siendo la fuente de verdad (`CLAUDE.md`) — este documento no propone desvíos, es la
lista de qué hay que corregirle a él mismo cuando se lo vuelva a tocar.

**Revisado el 11/10/2026, sexta pasada** contra el `.mdj` actual y contra
`db/init-multitenant.sql`. El `.mdj` no cambió desde el 22/09: las entradas 2 y 3 ya están
aplicadas en el diagrama y las 5 a 20 siguen pendientes. No queda ninguna columna sin tipo de dato en todo el archivo.

---

## 1 · Resuelto en esta pasada — sin acción

- **`analisis_documento.modelo`**: agregada, TEXT.
- **`dato_documento.analisis_documento_id`**: ya tiene tipo (BIGINT, FK).
- **`atribucion_liquidacion.monto_maximo`/`fecha_actualizacion`**: ya tienen tipo.
- Las seis tablas nuevas, `poliza.suma_asegurada`/`cobertura_id`, y todo lo de las pasadas
  anteriores — sigue resuelto.

---

## 2 · `atribucion_liquidacion.actualizado_por` — decisión tomada: se suma al código

**Cerrado el 20/09/2026:** se decidió sumar `updated_by` a `settlement_authority` en vez de sacar
la columna del DER. Implementado:

- `db/init-multitenant.sql` y `db/migrations/2026-09-20-atribucion-quien-actualizo.sql` —
  `updated_by BIGINT REFERENCES arbiter_common.users(id)`, nullable (las filas de antes de este
  cambio quedan en NULL, no hay con qué completarlas retroactivamente).
- `SettlementAuthority.java` — campo `updatedBy`.
- `SettlementAuthorityService.set()` — lo completa con `currentUserId()`, mismo patrón que
  `CaseServiceImpl` usa para `case_settlement.authorized_by_user_id` (resuelve el mail del JWT
  contra `arbiter_common.users`).
- `SettlementAuthorityResponse.updatedByUserId` — expuesto en el `GET`.

**Falta del lado del DER:** marcar `actualizado_por` como FK → `usuario.id` (hoy tiene el tipo
BIGINT pero no el flag de FK).

---

## 3 · `liquidacion.fecha_autorizacion` — sigue marcada como FK

Es `TIMESTAMPTZ` nullable, la fecha en que el referente autorizó, no una referencia a otra tabla.
Sacarle la marca de FK.

---

## 4 · `peritaje.expediente_id` — la UNIQUE compuesta, desestimada

**Encontrado:** 19/09/2026, al construir la métrica "respuesta de terceros" del tablero del
referente (`ClaimMetricsRepository`, agrupa por `provider_type`/`tipo_proveedor`).

Un expediente puede tener **hasta dos** filas de `peritaje` — una por perito
(`ESTUDIO_LIQUIDADOR`) y otra por servicio técnico (`SERVICIO_TECNICO`), porque un peritaje que
descarta fraude puede derivar después a reparación.
`db/migrations/2026-09-11-derivacion-a-reparacion.sql` cambió el UNIQUE real de `(case_id)` a
`(case_id, provider_type)`.

**Desestimado el 20/09/2026:** StarUML no dejó modelar la UNIQUE compuesta sobre `peritaje`. Ya se
sacó la UNIQUE de una sola columna sobre `expediente_id`, que era la parte que efectivamente
contradecía la implementación — la restricción compuesta en sí queda sin representar en el `.mdj`.

**Que quede anotado en algún lado no formal del diagrama** (nota de texto junto a `peritaje`, o
similar) que la cardinalidad real es `expediente 0..1—0..2 peritaje`, no `1—1` — para que quien lo
lea no asuma la UNIQUE simple que ya no existe.

*Nota: `analista_declarante_id` y `expediente_id` de `antecedente_fraude` tampoco tienen la marca
de FK puesta. No lo marco como bache nuevo porque no toca nada de lo trabajado esta semana — es de
la pasada del 10/09 y quedó afuera de esa revisión; queda para la próxima vez que se toque esa
entidad.*

**Nota general sobre UNIQUE en columnas FK:** no se pide como acción en este documento — la
cardinalidad 1:1 va en la relación del diagrama (el extremo con `1` en vez de `0..*`), no como
propiedad `unique` de la columna. La FK ya dice de qué tabla depende; la relación dice cuántas
puede haber.

---

## 5 · `analisis_documento.hecho_generador_descripto` — columna nueva, falta en el DER

**Encontrado:** 22/09/2026. No es un bache del diagrama sino una columna que el código ya tiene:
se sumó para comparar el hecho generador que narra un documento contra el declarado (regla
`CLAIM_CAUSE_MATCH`, que avisa sin bloquear el Fast Track). Implementada en
`db/init-multitenant.sql` y `db/migrations/2026-09-22-hecho-descripto-en-documento.sql`, ya
aplicada en Railway.

| Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|
| `hecho_generador_descripto` (`described_claim_cause`) | VARCHAR(120) | sí | ninguna — **sin FK** a `hecho_generador` |

Sin FK a propósito, igual que `analisis_llm.hecho_generador_sugerido` (`suggested_claim_cause`): es un
registro de auditoría de lo que leyó la extracción, y tiene que seguir diciéndolo aunque el
referente después renombre o dé de baja el hecho generador. NULL es el valor normal: la mayoría de
los documentos (facturas, constancias técnicas) no narran ningún hecho.

**Acción:** agregar la columna a `analisis_documento` en el `.mdj`.

*Nota: `resultado_regla.tipo_regla` suma el literal `CLAIM_CAUSE_MATCH`. No hace falta tocar el
DER por eso: la columna es texto libre sin CHECK, y el vocabulario vive en `RuleType`.*

---

## 6 · `analisis_documento.estado_extraccion` — columna nueva, falta en el DER

**Encontrado:** 23/09/2026. Columna que suma el código: dice si la lectura del documento funcionó.
Sin ella, una respuesta rota del modelo (cortada por el tope de tokens) guardaba los campos vacíos y
se veía igual que un documento que no dice nada. Implementada en `db/init-multitenant.sql` y
`db/migrations/2026-09-23-estado-extraccion-documento.sql`. Aplicada en Railway (verificado el 05/10/2026).

| Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|
| `estado_extraccion` (`extraction_status`) | VARCHAR(20) | no | default `'COMPLETE'`; CHECK `IN ('COMPLETE', 'PARTIAL', 'FAILED')` |

**Acción:** agregar la columna a `analisis_documento` en el `.mdj`.

---

## 7 · `regla_aseguradora.creado_por` y `configuracion_scoring.creado_por` — columnas nuevas, faltan en el DER

**Encontrado:** 25/09/2026. El historial de cambios de reglas arranca cada regla con su creación, y
no había dónde saber quién la creó: `historial_regla_aseguradora.modificado_por` solo se completa en
los cambios. Implementadas en `db/init-multitenant.sql` y
`db/migrations/2026-09-25-regla-quien-la-creo.sql`. Aplicadas en Railway (verificado el 05/10/2026).

| Tabla | Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `regla_aseguradora` | `creado_por` (`created_by`) | BIGINT | no | FK → `usuario.id` |
| `configuracion_scoring` | `creado_por` (`created_by`) | BIGINT | no | FK → `usuario.id` |

Apuntan a `usuario` y no a `referente_aseguradora` a propósito: el autor es siempre un usuario, sin
atarlo a qué rol puede configurar reglas hoy. Una aseguradora nueva nace sin reglas; las de las
aseguradoras de demo (`db/seed-demo.sql`) quedan a nombre de su referente. La
migración completa las filas que ya existían con el usuario del referente del primer cambio de la
regla, o el del primer referente de la aseguradora.

**Acción:** agregar las dos columnas en el `.mdj` como FK obligatoria a `usuario`.

---

## 8 · `cobertura.base_franquicia` y `liquidacion.base_franquicia` — columnas nuevas, faltan en el DER

**Encontrado:** 28/09/2026. Las suma el PR #120 (Martina): cada cobertura dice sobre qué se calcula
su franquicia. BBVA la toma de la suma asegurada ("10% de la suma asegurada"), y el mercado, del
siniestro (SURA art. 6, Meridional cl. 6). `liquidacion` guarda la base que usó, igual que el resto
de los datos de entrada. Implementadas en `db/init-multitenant.sql` y
`db/migrations/2026-09-28-base-de-la-franquicia.sql`.

| Tabla | Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `cobertura` | `base_franquicia` (`deductible_basis`) | VARCHAR(20) | no | default `'SUM_INSURED'`; CHECK `IN ('SUM_INSURED', 'LOSS_AMOUNT')` |
| `liquidacion` | `base_franquicia` (`deductible_basis`) | VARCHAR(20) | no | default `'SUM_INSURED'`; mismo CHECK |

**Acción:** agregar la columna a `cobertura` y a `liquidacion` en el `.mdj`.

---

## 9 · `expediente.fue_fast_track` → `expediente.clasificacion_por_reglas` — cambia la columna

**Encontrado:** 29/09/2026. Fast Track era una de las cuatro salidas que decide el motor de reglas sin
el modelo. Las otras tres (exclusión de cobertura, prescripción y falta de documentación) se
guardaban en `analisis_llm` como si las hubiera recomendado el modelo. Ahora ninguna escribe en
`analisis_llm`: la recomendación del motor queda en el expediente y el porqué, en
`resultado_regla`. Implementado en `db/init-multitenant.sql` y
`db/migrations/2026-09-29-quien-decidio-la-clasificacion.sql`.

| Tabla | Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `expediente` | ~~`fue_fast_track`~~ (`was_fast_track`) | BOOLEAN | — | se dropea |
| `expediente` | `clasificacion_por_reglas` (`rules_classification`) | VARCHAR(50) | sí | CHECK `IN ('FAST_TRACK', 'FALTA_DOCUMENTACION', 'LLM_SOLICITA_REVISION_MANUAL', 'LLM_NO_RECOMIENDA_APROBAR')`; nula cuando decidió el modelo |

**Acción:** reemplazar `fue_fast_track` por `clasificacion_por_reglas` en `expediente` en el `.mdj`.

---

## 10 · `perito` y `peritaje` — renombre a nombres neutrales y ramos N:M

**Encontrado:** 01/10/2026. El catálogo sirve a estudios liquidadores y a servicios técnicos
(`tipo_proveedor`), pero las dos tablas estaban nombradas como si solo hubiera peritos, y cada
proveedor cubría un solo ramo o todos. Implementado en `db/init-multitenant.sql` y
`db/migrations/2026-10-01-proveedor-externo.sql`. Aplicado en Railway el 05/10/2026.

| Antes | Después | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `perito` | `proveedor_externo` (`service_provider`) | — | — | tabla renombrada |
| `perito.rama_id` | — | — | — | se dropea: pasa a `proveedor_externo_rama` |
| — | `proveedor_externo_rama.proveedor_externo_id` (`service_provider_id`) | BIGINT | no | PK compuesta; FK → `proveedor_externo.id`, ON DELETE CASCADE |
| — | `proveedor_externo_rama.rama_id` (`branch_id`) | BIGINT | no | PK compuesta; FK → `rama.id` |
| `peritaje` | `derivacion_expediente` (`case_referral`) | — | — | tabla renombrada |
| `peritaje.nombre_perito` | `derivacion_expediente.nombre_proveedor` (`provider_name`) | VARCHAR(120) | no | — |
| `peritaje.email_perito` | `derivacion_expediente.email_proveedor` (`provider_email`) | VARCHAR(150) | no | — |
| `peritaje.perito_id` | `derivacion_expediente.proveedor_id` (`provider_id`) | BIGINT | sí | FK → `proveedor_externo.id`, ON DELETE SET NULL |
| `antecedente_fraude.peritaje_id` | `antecedente_fraude.derivacion_expediente_id` (`case_referral_id`) | BIGINT | sí | FK → `derivacion_expediente.id` |

Un proveedor sin filas en `proveedor_externo_rama` es generalista: cubre todos los ramos, también
los que se den de alta después. La migración pasa cada `rama_id` no nulo a una fila de la tabla
nueva; los que tenían NULL quedan sin filas. `veredicto`, `monto_indemnizable`,
`resultado_reparacion` y `costo_reparacion` no cambian: ya son propios de un tipo de proveedor.

**Acción:** en el `.mdj`, renombrar las dos entidades y sus columnas, sacar `rama_id` de
`proveedor_externo` y agregar `proveedor_externo_rama` como N:M entre `proveedor_externo` y `rama`.

---

## 11 · `rama` y `hecho_generador` — pasan a ser de cada aseguradora

**Encontrado:** 05/10/2026. Las dos tablas eran un catálogo de la plataforma: que un referente
renombrara o borrara un ramo se lo cambiaba a todas las aseguradoras. Ahora cada esquema de
aseguradora tiene las suyas, copiadas con los mismos ids. Implementado en `db/init-multitenant.sql`
y `db/migrations/2026-10-05-ramos-por-aseguradora.sql`. Aplicado en Railway el 06/10/2026.

| Tabla | Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `rama` (`branch`) | `id` | BIGINT | no | PK; ahora única por aseguradora, no en la plataforma |
| `rama` | `nombre` (`name`) | VARCHAR(100) | no | UNIQUE dentro de la aseguradora |
| `rama` | `nombre_externo` (`external_name`) | VARCHAR(100) | sí | UNIQUE; columna nueva |
| `hecho_generador` (`claim_cause`) | `id` | BIGINT | no | PK |
| `hecho_generador` | `nombre` (`name`) | VARCHAR(100) | no | UNIQUE junto con `rama_id` |
| `hecho_generador` | `rama_id` (`branch_id`) | BIGINT | no | FK → `rama.id` de la misma aseguradora |

`nombre_externo` es como la BD de la aseguradora llama al ramo (`poliza.rama`). Las pólizas se cruzan
por esa columna y no por `nombre`, así el referente puede renombrar un ramo sin que las pólizas
dejen de encontrarlo. Nace igual a `nombre` y un rename no la toca.

Las FK que apuntaban al catálogo común (`cobertura`, `regla_aseguradora`, `requisito_documental`,
`atribucion_liquidacion`, `expediente`, `proveedor_externo_rama`) ahora apuntan a las tablas de la
misma aseguradora: en el diagrama las relaciones no cambian, cambia de qué lado están las entidades.

**Acción:** en el `.mdj`, mover `rama` y `hecho_generador` de la parte común a la parte por
aseguradora y agregar `rama.nombre_externo`.

---

## 12 · `hecho_generador.nombre` — la UNIQUE es junto con `rama_id`, no sola

**Encontrado:** 05/10/2026, al cotejar la entrada 11 contra el `.mdj`. No es de esa historia: el
esquema ya era así antes.

En el DER `hecho_generador.nombre` está marcada como única por sí sola. En el esquema la unicidad es
`(rama_id, nombre)` (`claim_cause_branch_name_unique`), porque el mismo hecho se repite entre ramos a
propósito: "Robo en vía pública" y "Hurto" existen una vez en Celulares y otra en Tecnología
Portátil, cada una con su documentación requerida y su cobertura.

| Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|
| `nombre` (`name`) | VARCHAR(100) | no | UNIQUE junto con `rama_id`; hoy el DER la marca única sola |

`rama.nombre` sí es única sola, dentro de la aseguradora: ahí el DER está bien.

**Acción:** en el `.mdj`, sacar la marca `unique` de `hecho_generador.nombre`. Si StarUML no deja
modelar la compuesta (pasó con `peritaje`, entrada 4), dejar una nota de texto junto a la entidad:
un nombre no se repite dentro de un ramo, pero sí entre ramos.

---

## 13 · Columnas que el código tiene y el DER no

**Encontrado:** 07/10/2026, en una pasada completa del `.mdj` contra `db/init-multitenant.sql`. Las
tres son anteriores a las entradas 5 a 12; no las había cruzado nadie.

| Tabla | Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `analisis_llm` | `fecha_analisis` (`analyzed_at`) | TIMESTAMPTZ | no | default `NOW()` |
| `cobertura` | `es_individual` (`is_individual`) | BOOLEAN | no | default `TRUE` |
| `expediente` | `destino` (`destination`) | VARCHAR(40) | sí | — |

**Acción:** agregar `analisis_llm.fecha_analisis` al `.mdj`: es la marca temporal que pide la
auditoría de clasificaciones, y `analisis_imagen` y `analisis_riesgo_expediente` ya tienen la suya.

**Decisión pendiente** para las otras dos, porque ninguna se usa de verdad:

- `cobertura.es_individual`: el alta de cobertura la guarda siempre en `TRUE` y nada la lee.
- `expediente.destino`: nada la escribe; el comentario de la entidad dice que sus valores nunca se
  definieron.

O se suman al DER con una semántica definida, o se dropean del esquema y de las entidades.

---

## 14 · `aseguradora.logo` y la entidad `metrica` — están en el DER y no en el código

**Encontrado:** 07/10/2026. Es el caso inverso al habitual.

| Tabla | Columna | Tipo de dato | En el esquema |
|---|---|---|---|
| `aseguradora` | `logo` | BYTEA | no existe |
| `metrica` | entidad completa (6 columnas) | — | no existe la tabla |

`metrica` modela un valor precalculado por métrica y período. Lo que terminó implementado es otra
cosa: las cuatro tablas de la entrada 20, así que `metrica` se resuelve ahí.

**Decisión pendiente** solo para `aseguradora.logo`: sacarla del DER, o dejarla como algo a
implementar con una nota en el diagrama que diga que todavía no existe.

---

## 15 · Marcas de FK que faltan

**Encontrado:** 07/10/2026. Las dos de `antecedente_fraude` ya estaban en la nota de la entrada 4;
quedan acá para que tengan su acción.

| Tabla | Columna | Apunta a |
|---|---|---|
| `hecho_generador` | `rama_id` | `rama.id` |
| `antecedente_fraude` | `analista_declarante_id` | `analista_siniestro.id` |
| `antecedente_fraude` | `expediente_id` | `expediente.id` |

**Acción:** marcar las tres como FK en el `.mdj`.

---

## 16 · Tipos y largos que no coinciden con el esquema

**Encontrado:** 07/10/2026. Las pasadas anteriores revisaron que cada columna tuviera tipo, no que
el tipo coincidiera.

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

Los que más importan son los que en el DER no alcanzan para lo que ya se guarda: un DNI con puntos
(`42.987.654`) no entra en 8 caracteres, y `LLM_SOLICITA_REVISION_MANUAL` no entra en 20.

Dos nombres con error de tipeo: `analisis_imagen.modelo_utlizado` (por `modelo_utilizado`) y
`poliza_consultada.canitdad_siniestros_previos` (por `cantidad_siniestros_previos`).

**Acción:** corregir tipos, largos y los dos nombres en el `.mdj`.

---

## 17 · UNIQUE de una sola columna que faltan

**Encontrado:** 07/10/2026. Ninguna es una FK, así que no les aplica la nota general de la entrada
4: la unicidad es de la columna y va marcada en ella.

| Tabla | Columna | Restricción en el esquema |
|---|---|---|
| `rol` | `codigo` | UNIQUE |
| `permiso` | `nombre` | UNIQUE |
| `estado_expediente` | `nombre` | UNIQUE |
| `asegurado` | `dni` | UNIQUE |
| `poliza` | `nro_poliza_externo` | UNIQUE |

**Acción:** marcar las cinco como `unique` en el `.mdj`.

*Nota: el esquema tiene además dos UNIQUE compuestas sin representar, `cobertura_contratada
(poliza_id, cobertura_id)` y `documento_expediente (expediente_id, tipo)`. No las pido como acción
por lo mismo que la de `peritaje` en la entrada 4.*

---

## 18 · `cobertura.nombre_externo` — columna nueva, y la UNIQUE de `nombre` pasa a ser por ramo

**Encontrado:** 10/10/2026. El nombre de la cobertura era la clave de cruce con la BD de la
aseguradora (`cobertura.nombre`), así que renombrarla desde el panel hacía que las pólizas dejaran de
encontrarla. Implementado en `db/init-multitenant.sql` y
`db/migrations/2026-10-10-nombres-de-cobertura.sql`. **Sin aplicar en Railway.**

| Tabla | Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `cobertura` (`coverage`) | `nombre` (`name`) | VARCHAR(100) | no | UNIQUE junto con `rama_id` (antes sola) |
| `cobertura` | `nombre_externo` (`external_name`) | VARCHAR(160) | sí | UNIQUE junto con `rama_id`; columna nueva |

`nombre_externo` es como la BD de la aseguradora llama a la cobertura. Nace igual a `nombre` y un
rename no la toca, igual que `rama.nombre_externo` (entrada 11). Las dos UNIQUE son por ramo porque
esa base repite un mismo nombre entre ramos ("Robo de celular" también cubre una notebook).

La misma migración cambia datos, sin impacto en el diagrama: la cobertura "Robo de celular" pasa a
llamarse "Robo", y en Tecnología Portátil el hecho generador "Daño accidental" pasa a "Rotura
accidental" y se suma "Caída".

**Acción:** agregar `cobertura.nombre_externo` en el `.mdj`. Las dos UNIQUE son compuestas, así que
no se marcan en la columna (misma nota que la entrada 12).

---

## 19 · `atributo_bien` — tabla nueva en la BD de la aseguradora

**Encontrado:** 11/10/2026. Lo único que describía el bien asegurado era `poliza.bien_asegurado`,
texto libre, y el score de fraude buscaba adentro la marca y el modelo de cada documento: "Galaxy
A56 5G" no aparecía en "Samsung Galaxy A56" y sumaba como inconsistencia. Implementado en
`db/init-multitenant.sql` y `db/migrations/2026-10-11-atributos-del-bien.sql`. **Aplicada en Railway
el 11/10/2026.**

| Tabla | Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `atributo_bien` | `id` | BIGINT | no | PK |
| `atributo_bien` | `poliza_id` | BIGINT | no | FK a `poliza` de la BD de la aseguradora |
| `atributo_bien` | `nombre` | VARCHAR(60) | no | UNIQUE junto con `poliza_id` |
| `atributo_bien` | `valor` | VARCHAR(255) | no | |

Una fila por dato que identifica al bien (`marca` → `Samsung`, `modelo` → `Galaxy A56`). Son filas y
no columnas de `poliza` porque cada ramo identifica sus bienes con datos distintos. Vive en los
esquemas `aseguradora_*`, no en los de Arbiter.

**Acción:** decisión pendiente — sumarla al `.mdj` solo si el diagrama incluye las tablas de la BD de
la aseguradora. La UNIQUE es compuesta, así que no se marca en la columna (misma nota que la entrada
12).

---

## 20 · `metrica_dia` y las tres `metrica_diaria_*` — tablas nuevas de reportes

**Encontrado:** 11/10/2026, al repasar las migraciones posteriores a la quinta pasada. La
`2026-10-05-metricas-de-reportes.sql` no tenía entrada. `reports-service` ahora guarda los totales de
los días ya cerrados, para armar un período sumando días en vez de volver a los expedientes. Son
cuatro tablas por aseguradora, también en `db/init-multitenant.sql`.

| Tabla | Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `metrica_dia` (`metrics_day`) | `dia` (`day`) | DATE | no | PK |
| `metrica_dia` | `fecha_calculo` (`computed_at`) | TIMESTAMPTZ | no | default `NOW()` |
| `metrica_diaria_ingreso` (`metrics_daily_intake`) | `dia` (`day`) | DATE | no | FK → `metrica_dia.dia`, ON DELETE CASCADE |
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

Las tres `metrica_diaria_*` no tienen `id`: son una fila por día, hecho generador y analista
asignado. Guardan sumas y conteos, nunca una tasa ni un promedio. `metrica_dia` marca qué días
están guardados, aunque ese día no haya pasado nada.

Esto reemplaza a la entidad `metrica` que hoy tiene el DER (entrada 14): esa modelaba un valor por
métrica y período, y lo implementado guarda los componentes por día.

**Acción:** en el `.mdj`, sacar `metrica` y agregar las cuatro tablas, con `metrica_dia` 1—N contra
cada `metrica_diaria_*` y `hecho_generador` 1—N contra cada una.

---

## 21 · `atributo_documento` — tabla nueva, y `analisis_documento` deja de usar `marca`, `modelo` e `imei`

**Encontrado:** 11/10/2026. Lo que un documento dice sobre el bien se guardaba en tres columnas fijas
de `analisis_documento` (`document_analysis.brand`, `model`, `imei`), que solo sirven para un celular
o una notebook. Ahora cada documento se lee buscando los datos con que la póliza identifica a su bien
(`atributo_bien`, entrada 19) y se guarda una fila por dato. Implementado en
`db/init-multitenant.sql` y `db/migrations/2026-10-11-atributos-del-documento.sql`. **Sin aplicar en
Railway.**

| Tabla | Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|---|
| `atributo_documento` (`document_attribute`) | `id` | BIGINT | no | PK |
| `atributo_documento` | `nombre` (`name`) | VARCHAR(60) | no | |
| `atributo_documento` | `valor` (`value`) | TEXT | no | |
| `atributo_documento` | `analisis_id` (`analysis_id`) | BIGINT | no | FK a `analisis_documento`, borrado en cascada |

Relación: un `analisis_documento` tiene de 0 a N `atributo_documento`, igual que con
`detalle_documento`. La diferencia es que estos sí se comparan: el nombre es el de la póliza
(`marca`, `modelo`, `IMEI`, mañana `patente`), no el que eligió el modelo.

La misma migración copia a la tabla nueva lo ya leído y pasa el IMEI de las pólizas a `atributo_bien`.

**Acción:** agregar `atributo_documento` en el `.mdj`, con su relación a `analisis_documento`. Las
columnas `marca`, `modelo` e `imei` de `analisis_documento` quedan en la base sin usar hasta que salga
el deploy y se borran en una migración posterior: sacarlas del diagrama recién ahí.

---

## Plantilla para la próxima entrada

```
## `tabla.columna` — descripción corta del bache

**Encontrado:** fecha.

| Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|
| ... | ... | ... | ... |

**Acción:** agregar / dropear / corregir tipo / decisión pendiente (con quién hay que confirmarla).
```
