# Historias de usuario — enhancements pendientes

**Fecha:** 10/08/2026 · **Origen:** lo que quedó abierto al cerrar los 31 defectos del
[handoff de pruebas y defectos](handoff-pruebas-y-defectos.md).

Cada bloque es **una card de Trello**: el título va en el nombre de la card y el resto en la
descripción. La numeración arranca en H0021 asumiendo que el documento de HU llega hasta H0020 —
confirmar antes de cargarlas.

Ordenadas por lo que más rinde. Las tres primeras se destraban entre sí.

---

## H0021 · Reglas duras configurables por la aseguradora

**Como** referente de la aseguradora
**quiero** configurar los umbrales de las reglas duras (plazo de denuncia policial, carencia, tope de
eventos, vigencia) desde el panel
**para** ajustar la operación de mi compañía sin depender de un deploy.

**Criterios de aceptación**
- El referente puede ver y editar cada umbral desde el panel de reglas.
- Un cambio de umbral impacta en la próxima clasificación, sin reiniciar servicios.
- Cada regla evaluada deja fila en `rule_result` con su resultado (PASS y FAIL), igual que hoy lo
  hacen las exclusiones de cobertura.
- Una aseguradora sin la regla configurada se comporta como hoy: la regla no se evalúa.

**Por qué importa**
Es la card que desbloquea las otras dos cosas que quedaron a medias:
1. El plazo de la denuncia policial hoy está **hardcodeado en 72 hs** (propiedad
   `arbiter.rules.police-report-deadline-hours`). Contradice la decisión #12 del `CLAUDE.md`.
2. **Ninguna de las reglas duras temporales se audita** (D9/D10/D11/D12/D13): sus umbrales son
   columnas de `coverage`, y `rule_result.rule_id` es FK NOT NULL a `insurer_rule`, así que no tienen
   a qué apuntar. Sin esto, la auditoría de la Disposición SSN 2/2023 cubre las exclusiones de
   cobertura y nada más.

**Notas técnicas**
Modelarlas como filas de `insurer_rule` con `rule_type` propio y configuración en el JSONB, igual que
`COVERAGE_EXCLUSION` (ver `plan-reglas-evaluables.md` §1.1). Las columnas de `coverage` pasan a ser
el default cuando no hay regla, o se migran.

---

## H0022 · Extraer del relato del asegurado los datos que las reglas necesitan

**Como** analista de siniestros
**quiero** que el sistema detecte en la descripción del siniestro los hechos que definen la cobertura
**para** que un caso no cubierto no llegue a mi bandeja como si lo estuviera.

**Criterios de aceptación**
- La descripción que escribe el asegurado en el alta pasa por una extracción que devuelve **datos
  tipados**, no prosa.
- Si el relato indica que el damnificado es un familiar y la cobertura no alcanza al grupo familiar,
  el caso se deriva a revisión manual con el motivo a la vista.
- Ante la duda, el dato sale como `DESCONOCIDO` y la regla **no** se evalúa: la ambigüedad nunca
  cuenta en contra del asegurado.
- El modelo solo extrae; la decisión de cobertura la toma el código.

**Por qué importa**
`covers_family_group` quedó implementado **solo sobre los documentos adjuntos**. Si el asegurado
escribe "le robaron el celular a mi hijo" en la descripción y ningún documento lo menciona, la regla
no se entera. Es la mitad que falta de D9.

**Notas técnicas**
Misma forma que `OllamaDocumentAnalyzer` → `DocumentExtraction.Fields`, pero sobre `claim.description`.
Corre antes de la clasificación. Reusa `CoverageScopeEvaluator`, que ya evalúa la regla.

---

## H0023 · Cruzar el IMEI del documento contra el del equipo asegurado

**Como** analista de siniestros
**quiero** ver marcado cuando el IMEI de un documento no coincide con el del equipo asegurado
**para** detectar constancias fabricadas o de otro equipo.

**Criterios de aceptación**
- El bien asegurado tiene IMEI propio en el modelo de Arbiter, sincronizado desde la BD Aseguradora
  (hoy solo existe del lado de la aseguradora).
- El analista ve el IMEI del equipo en la ficha del expediente.
- La discrepancia aparece como factor del score de fraude con el detalle de ambos valores.

**Por qué importa**
El cruce ya funciona (D4b), pero el IMEI vive **solo en `aseguradora_*.poliza.imei`**: no está en la
`policy` local ni se muestra en ninguna pantalla. El analista ve el factor pero no puede verificarlo.

---

## H0024 · Ver los datos de la póliza sobre los que se clasificó

**Como** analista de siniestros
**quiero** ver la foto de la póliza tal como estaba cuando se clasificó el expediente
**para** entender por qué el sistema puntuó lo que puntuó, aunque los datos hayan cambiado después.

**Criterios de aceptación**
- El detalle del expediente muestra: suma asegurada, estado de pago, vigencia y cantidad de
  siniestros previos **al momento de clasificar**, con la fecha de esa consulta.
- Se distingue de los datos actuales de la póliza cuando difieren.

**Por qué importa**
La foto ya se guarda (`policy_snapshot`, D27) y **no la lee nadie**. Es lo que hace auditable y
reproducible una clasificación ante la Disposición SSN 2/2023 — pero solo si alguien puede verla.

---

## H0025 · Configurar las condiciones de la cobertura desde el panel

**Como** referente de la aseguradora
**quiero** editar la carencia, si la cobertura alcanza al grupo familiar y si un siniestro la agota
**para** que la configuración refleje lo que dice la póliza que vendemos.

**Criterios de aceptación**
- Los tres campos son visibles y editables en la solapa **Coberturas**, junto a los que ya están.
- El motor evalúa lo que quedó guardado.
- Se aclara en pantalla qué significa cada uno (la carencia sobre todo).

**Por qué importa**
El motor ya evalúa los tres (D9), pero el referente **no puede verlos ni cambiarlos**: se configuran
por SQL. La pantalla ya existe, es sumarle campos.

---

## H0026 · Avisarle al asegurado cuando cambia el estado de su expediente

**Como** asegurado
**quiero** recibir un mail cuando mi expediente cambia de estado
**para** enterarme sin tener que entrar a mirar.

**Criterios de aceptación**
- Cada transición de estado dispara una notificación al mail del asegurado.
- El mail dice el estado nuevo en lenguaje del asegurado, **sin** clasificación, score ni motivos
  internos.
- Queda registro de la notificación enviada.
- Un fallo de envío no rompe la transición del expediente.

**Por qué importa**
Es la decisión #9 del `CLAUDE.md`. `Notification` tiene entidad y repository con **cero escritores**;
SendGrid hoy solo se usa para invitar usuarios en auth-service.

---

## H0027 · Alertar el vencimiento del plazo legal de respuesta

**Como** analista de siniestros
**quiero** que el sistema me avise antes de que venza el plazo legal para expedirme
**para** no dejar vencer un expediente por olvido.

**Criterios de aceptación**
- El plazo arranca **cuando la documentación está completa**, no cuando se registra la denuncia.
- La bandeja muestra los expedientes próximos a vencer, y los vencidos.
- El plazo es configurable, no una constante de código.

**Por qué importa**
Doble problema. `response_deadline` se calcula al crear el expediente y **nunca se vuelve a leer** —
nada avisa cuando vence, y el silencio de la aseguradora equivale a aceptación. Y arranca el reloj en
el momento equivocado: el paper dice desde la documentación completa (ver
`gap-historias-usuario.md` §1), así que hoy adelanta el vencimiento y la alerta avisaría mal.

---

## H0028 · Reportes y tablero del referente

**Como** referente de la aseguradora
**quiero** ver métricas de los expedientes de mi compañía
**para** entender cómo viene operando y justificar cambios de configuración.

**Criterios de aceptación**
- Volumen de siniestros por período, ramo y estado.
- Distribución de clasificaciones y de bandas de riesgo.
- Tasa de Fast Track y tiempo promedio hasta la decisión del analista.
- Los datos son de la aseguradora del referente, nunca de otra.

**Por qué importa**
`reports-service` tiene solo la entidad `Metric` y su repository: **sin controllers, sin services**.
Las dos pantallas dicen "TODO MOCK" en su propio encabezado.

---

## H0029 · Conversación entre el asegurado y el analista

**Como** analista de siniestros
**quiero** pedirle aclaraciones al asegurado desde el expediente
**para** no resolver a ciegas ni tener que llamarlo por fuera del sistema.

**Criterios de aceptación**
- Hilo de mensajes por expediente, visible para las dos partes.
- El asegurado recibe aviso de un mensaje nuevo.
- Los mensajes quedan en la trazabilidad del expediente.

**Por qué importa**
La solapa "Conversación" del detalle del expediente es un **placeholder vacío**. Distinto de H0026:
esto es bidireccional.

---

# Decisiones de negocio (no son historias)

No se pueden estimar hasta que alguien del equipo las responda. Van como cards de decisión o se
resuelven en una reunión.

**1 · ¿Qué son `fraud_determined` y `destination`?**
Dos columnas de `cases` que están en el DER y no escribe nadie. Hoy el analista solo aprueba o
rechaza. `destination` no tiene valores definidos en ninguna fuente — el DER la dibuja como un
`VARCHAR(40)` suelto. ¿Son salidas reales del proceso (pago / rechazo / derivación a investigación) o
quedaron del modelo? Hasta que se conteste no se les inventa semántica.

**2 · ¿`is_individual` es la negación de `covers_family_group`?**
El seed es consistente con esa lectura (`covers_family_group=FALSE` / `is_individual=TRUE` en las
dos coberturas). Si lo es, hay que **dropearla del DER o derivarla**, no dejar las dos vivas: es el
mismo hecho guardado dos veces.

**3 · ¿Modelamos pólizas colectivas?**
`Tomador` y `N° de certificado` se sacaron de la ficha del expediente porque en nuestro modelo cada
póliza es individual y el certificado es 1:1 con ella. Vuelven las dos juntas si algún día se modela
la póliza colectiva — que es como BBVA vende de verdad, a través del banco.
