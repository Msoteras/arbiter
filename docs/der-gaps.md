# Baches del DER

Registro de lugares donde el DER (`docs/arbiter der.mdj`) tiene una columna, una relación o un dato
que no se sostiene contra lo implementado — redundante, sin semántica definida, o de un modelo
viejo que quedó atrás. **No son historias de desarrollo**: son correcciones al propio DER, y quedan
afuera del backlog de Trello por eso.

El DER sigue siendo la fuente de verdad (`CLAUDE.md`) — este documento no propone desvíos, es la
lista de qué hay que corregirle a él mismo cuando se lo vuelva a tocar.

**Revisado el 20/09/2026, cuarta pasada** contra el `.mdj` actual y contra
`db/init-multitenant.sql`. No queda ninguna columna sin tipo de dato en todo el archivo.

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
`db/migrations/2026-09-23-estado-extraccion-documento.sql`. **Todavía no aplicada en Railway.**

| Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|
| `estado_extraccion` (`extraction_status`) | VARCHAR(20) | no | default `'COMPLETE'`; CHECK `IN ('COMPLETE', 'PARTIAL', 'FAILED')` |

**Acción:** agregar la columna a `analisis_documento` en el `.mdj`.

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
