# Temas a discutir en equipo

No son historias de desarrollo ni baches del DER: son decisiones que necesitan una charla antes de
poder convertirse en una card de Trello (o en nada). A diferencia de `der-gaps.md`, esto no son
correcciones al modelo de datos — son alcance de producto sin cerrar.

Cada entrada: qué se sabe, qué falta decidir, y qué bloquea mientras siga sin decidirse.

---

## H0015 tras la decisión de transiciones fijas

**Qué se sabe:** las transiciones de estado del expediente van a ser **fijas**, no configurables
por aseguradora — decisión confirmada del equipo (26/08). Esto contradice al paper §2.2 (*"cada
transición... se valida contra el flujo definido por la aseguradora"*) y §3.1 (*"modificar...
flujos sin desarrollo"*), y a la lectura original de H0015 (*"el referente define los estados
activos y las transiciones permitidas"*).

**Qué falta decidir:**
- ¿H0015 queda acotada a "estados activos" + "plazos por estado", sin "transiciones permitidas"?
- ¿Hay algo de "estados activos" que tampoco vaya a ser configurable, o eso se mantiene?
- Cómo se ajusta el texto del paper (§2.2, §3.1) para no afirmar algo que el sistema no va a
  hacer.

**Bloquea:** la card de "estados faltantes del ciclo de vida" ya está lista para Trello con el
alcance acotado (transiciones fijas en código) — esto no la bloquea. Lo que bloquea es escribir
bien H0015 y ajustar el paper antes de la defensa.

---

## H0007 — Extracción de datos de documentos, alcance final

**Qué se sabe:** H0031 (hecha el 18/08) ya cubre el corazón de la historia — el modelo extrae
datos tipados y el analista los ve en la solapa "Datos extraídos". Lo que no cubre: los campos no
son exactamente los que pedía la HU original (falta nro. de factura, marca/modelo/serie por
separado), la solapa es de solo lectura (no se puede corregir un valor mal leído), y la validación
es solo contra la denuncia, no contra los datos de la póliza.

**Qué falta decidir:** si el equipo considera que lo ya implementado alcanza (y corresponde
reescribir la HU para que describa eso), o si vale la pena una card más chica con los tres
puntos puntuales que faltan (campos exactos + edición + cruce contra póliza).

**Bloquea:** nada urgente — es la única de las tres sin apuro real, pero conviene cerrarla antes
de dar la HU por completa en la documentación.

---

## El expediente se cuelga de la cobertura equivocada

**Encontrado:** 28/08/2026, verificando la solapa de trazabilidad (H #144) contra Railway.

**Qué se sabe:** un expediente hereda la cobertura de la póliza sin mirar qué se denunció, y la
póliza guarda una sola cobertura — siempre la primera que devuelve la compañía.

`PolicySynchronizer.resolveCoverage` toma *"the policy's primary coverage (the first one the
company returns)"*, y `CaseServiceImpl` hace `.coverage(policy.getCoverage())` al crear el caso. La
BD Aseguradora, en cambio, modela varias coberturas por póliza (`aseguradora_*.cobertura`, con
`orden`). Las 11 pólizas de BBVA en Arbiter tienen `coverage_id = 1` — todas apuntan a Robo.

Consecuencia medida: los tres expedientes de **Hurto** están colgados de la cobertura *Robo de
celular*, y la suma asegurada que se les congela es la de Robo.

| Caso | Póliza | Suma que se muestra | Suma real de Hurto |
|---|---|---|---|
| #10 | POL-CEL-2025-140 | $1.400.000 | $560.000 |
| #19 | POL-CEL-2026-260 | $500.000 | $200.000 |
| #39 | POL-CEL-2026-350 | $1.300.000 | $650.000 |

El número mal es lo visible, pero no es el fondo: `ClassificationServiceClient` le manda ese
`coverageId` al motor, así que un Hurto se evalúa contra los parámetros de Robo — carencia, plazo
de denuncia, tope de eventos, franquicia y la regla de inclusión de cobertura.

**Qué falta decidir:**
- Cómo se elige la cobertura del expediente: por el hecho generador denunciado (los catálogos ya
  coinciden por nombre entre `arbiter_*.coverage` y `aseguradora_*.cobertura`), o dejando que el
  asegurado la elija en el wizard.
- Si `arbiter_*.policy` pasa a tener varias coberturas —como la BD Aseguradora— o si se resuelve
  en el alta sin cambiar el modelo.
- Qué se hace con los expedientes ya creados: se recalculan, o quedan como están.

**Bloquea:** cambia resultados de reglas sobre expedientes existentes, así que necesita su propia
verificación y no conviene meterlo junto con otra cosa. Mientras tanto, la solapa de trazabilidad
muestra **de qué cobertura es la suma** para que el número no se lea como si fuera la del hecho
denunciado.

---

## La franquicia en una reparación deja casi todo en cero

**Encontrado:** 06/09/2026, implementando la fórmula de reparación (bloque 3 de la determinación
del monto a pagar).

**Qué se sabe:** la franquicia es un **porcentaje de la suma asegurada**, no del monto que se paga.
Es la lectura literal de las dos pólizas que relevamos y del ejemplo trabajado del manual de
Celulares ($300.000 asegurados → $30.000 de franquicia). Así está implementado en
`SettlementCalculator`, y así da el número del ejemplo del manual.

En pérdida total la regla es razonable: se descuenta una fracción de lo mismo que se indemniza. En
**reparación** no, porque el techo ya no es la suma asegurada sino el presupuesto:

| Suma asegurada | Franquicia 10% | Presupuesto de reparación | Se paga |
|---|---|---|---|
| $1.300.000 | $130.000 | $80.000 (cambio de pantalla) | **$0** |
| $1.300.000 | $130.000 | $200.000 | $70.000 |

Una pantalla rota —el siniestro más común del ramo— entra de lleno en el primer caso: la cobertura
de daño accidental no paga nunca nada por debajo de $130.000.

**Qué falta decidir:** si en la práctica las coberturas de daño calculan la franquicia sobre el
**presupuesto** en vez de sobre la suma asegurada. Nuestras dos pólizas no lo distinguen: las dos
hablan de una sola franquicia, y ninguna de las dos contempla explícitamente la reparación.

**Qué bloquea:** nada del desarrollo — la fórmula está implementada y anda. Bloquea poder decir que
el monto de una reparación es el que la compañía pagaría. Si la respuesta es "sobre el
presupuesto", el cambio es una línea en `SettlementCalculator` —la que hoy hace
`percentageOf(sumInsured, deductibleRate(...))`— más un interruptor por cobertura, como los que ya
tiene.
