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

## El ramo Tecnología Portátil de Provincia cubre un solo hecho generador

**Encontrado:** 05/09/2026, corriendo `init-multitenant.sql` + `seed-demo.sql` sobre un Postgres
limpio para verificar el seed de los criterios de Fast Track (H0038).

**Qué se sabe:** Provincia tiene una sola cobertura para Tecnología Portátil —*Daño accidental*— y
su lista negra excluye los hechos generadores 7 (Robo en vía pública) y 8 (Hurto). Como cada hecho
lo tiene que responder alguna cobertura, **un robo o un hurto de una notebook no lo cubre nadie**:
el selector del wizard filtra por esas mismas listas y no puede ofrecerlos, y si igual entrara un
expediente, el motor lo resolvería como exclusión de cobertura.

La intención parece haber sido otra: el encabezado de `db/datos-aseguradoras.sql` dice que el ramo
tiene las tres coberturas (Robo de celular, Hurto, Daño accidental), y la agenda documental
(`document_requirement`) ya tiene cargados los requisitos de los tres hechos.

Es el mismo agujero que tenía Celulares en BBVA con *Rotura accidental* y *Caída*, cerrado el
05/09 agregando la cobertura *Daño accidental* al ramo (ver H0038 en
`historias-proximo-sprint.md`). Acá no se hizo lo mismo por dos razones: **ningún expediente del
fixture está afectado** —no hay denuncias de robo ni hurto de equipo portátil— y las sumas
aseguradas y franquicias de las coberturas nuevas son un dato de negocio que no podemos inventar.

**Qué falta decidir:**
- Si el producto de Provincia efectivamente cubre robo y hurto de equipo portátil, o si esa
  restricción es real y lo que hay que corregir es el encabezado del script y la agenda documental.
- Con qué suma asegurada y franquicia entra cada cobertura nueva en cada póliza del ramo.
- Si Provincia además debería cubrir *Rotura accidental* y *Caída* en Celulares: hoy tampoco las
  cubre ninguna cobertura suya, y tampoco hay expedientes afectados. Es la misma decisión, un ramo
  más abajo — y ojo, es de Provincia: BBVA ya quedó cerrado.

**Bloquea:** nada hoy. Bloquearía a cualquiera que quiera demostrar un robo o un hurto de notebook
en Provincia, que es un caso perfectamente razonable de mostrar en la defensa.

**Se va a ver todas las noches hasta que se decida.** La BD Aseguradora de Provincia sí tiene una
cobertura de robo en dos pólizas de Tecnología (bajo el nombre "Robo de celular", que es el único
literal de robo que admite el CHECK de `cobertura.nombre`). Como el ramo no la tiene configurada del
lado de Arbiter, `PolicyResyncScheduler` la va a saltear y dejar el warning *"coverage(s) not
configured on this tenant for its branch, skipped: [Robo de celular]"* en cada corrida. Es
deliberado: sin el chequeo de ramo, el sync le colgaría a una notebook la cobertura de Celulares,
con sus plazos y su carencia.
