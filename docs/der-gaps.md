# Baches del DER

Registro de lugares donde el DER (`docs/arbiter der.mdj`) tiene una columna, una relación o un dato
que no se sostiene contra lo implementado — redundante, sin semántica definida, o de un modelo
viejo que quedó atrás. **No son historias de desarrollo**: son correcciones al propio DER (dropear
una columna, documentar una derivación, resolver una ambigüedad de una vez), y quedan afuera del
backlog de Trello por eso.

El DER sigue siendo la fuente de verdad (`CLAUDE.md`) — este documento no propone desvíos, es la
lista de qué hay que corregirle a él mismo cuando se lo vuelva a tocar.

Cada entrada: qué se encontró, por qué es un bache, y qué acción corresponde (dropear / derivar /
documentar / decisión pendiente).

---

## `arbiter_provincia`/`arbiter_bbva`.coverage — `is_individual` es la negación de `covers_family_group`

**Encontrado:** 26/08/2026, planificando el sprint 9.

El seed es consistente con esa lectura: `covers_family_group=FALSE` ⇔ `is_individual=TRUE` en las
dos coberturas configuradas. Son el mismo hecho guardado dos veces con signo cambiado.

**Confirmado por el equipo:** sí, es la negación.

**Acción:** dropear `is_individual` del DER (y de la tabla, si el drop no rompe nada que la lea) o
dejarla como columna derivada documentada explícitamente como tal — no mantener las dos vivas como
si fueran datos independientes.

---

## `cases.destination` — sin semántica definida, candidata a estar muerta

**Encontrado:** 09/08/2026 (barrido original) · revisado 26/08/2026.

`VARCHAR(40)` suelto en el DER, sin valores definidos en ninguna fuente (ni HU, ni paper, ni
código). `grep` sobre todo el código no devuelve ninguna referencia — nadie la lee ni la escribe.

**Actualización 26/08:** la funcionalidad que `destination` probablemente pretendía cubrir (derivar
un siniestro a investigación, marcarlo como pagado, etc.) ya se construyó por otro lado —
`FraudRecordService`/`cases.fraud_determined` para la determinación de fraude, `ExpertAssessment`
para la derivación a perito, los estados del ciclo de vida para el resto. `destination` no participa
de ninguno de esos flujos.

**Acción:** pendiente de confirmar con el equipo, pero la hipótesis de trabajo es dropearla —
quedó de un modelo anterior que la funcionalidad real terminó reemplazando por columnas y tablas
más específicas.

---

## `case_message` — la tabla existe y el DER no la tiene

**Encontrado:** 31/08/2026, al implementar la conversación entre el asegurado y el analista (H0034).

El DER modela `notificacion` (saliente y automática) pero ninguna entidad de conversación. La
implementación agregó `case_message` en cada esquema de aseguradora: `case_id`, `sender_id` →
`arbiter_common.users`, `sender_role` (INSURED/ANALYST, congelado al escribir), `body`,
`created_at`, `read_at`. Es una tabla por tenant, dueña de `cases-service`, y no reemplaza a
`notificacion`: esa sigue existiendo y ahora también registra los avisos de mensaje nuevo.

**Acción:** agregarla al DER. No hay ambigüedad que resolver ni decisión pendiente — es un
faltante, y el esquema ya está en `db/init-multitenant.sql` y aplicado.

---

## Plantilla para la próxima entrada

```
## `tabla.columna` — descripción corta del bache

**Encontrado:** fecha.

Qué dice el DER vs. qué hace el código/no hace nadie.

**Acción:** dropear / derivar / documentar / decisión pendiente (con quién hay que confirmarla).
```
