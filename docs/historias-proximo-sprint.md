# Historias de usuario — próximo sprint

**Origen:** hallazgos de la tanda de fixes del 31/08–01/09 que no entraban en esa entrega.

Cada bloque es **una card de Trello**: el título va en el nombre de la card y el resto en la
descripción. La numeración sigue desde H0034, el último de `historias-enhancements.md`.

---

## H0035 · La lectura de la clasificación trae todo el historial para quedarse con una fila

**Como** analista de siniestros
**quiero** que abrir un expediente lea solo la clasificación vigente
**para** que el detalle no se vuelva más lento con cada reintento y el log deje de llenarse de
advertencias que tapan las que sí importan.

**Criterios de aceptación**
- Abrir el detalle de un expediente deja de emitir `HHH90003004: firstResult/maxResults specified
  with collection fetch; applying in memory`.
- La consulta trae **una** fila de `llm_analysis` con sus `llm_reason`, no todas las del expediente.
- El detalle sigue mostrando exactamente la misma clasificación que hoy: la de la **última** corrida,
  con todos sus motivos. Un expediente reclasificado varias veces sigue mostrando la más reciente.
- Un expediente sin clasificación (Fast Track, o todavía en curso) sigue resolviendo sin error.
- El comportamiento queda cubierto por un test contra Postgres real, no solo con el repositorio
  mockeado: los unitarios de hoy no ven la diferencia porque el problema es cómo Hibernate arma
  el SQL.

**Por qué importa**
`LlmAnalysisRepository.findFirstByCaseIdOrderByIdDesc` combina `findFirst` —que Spring Data traduce
a `maxResults = 1`— con `@EntityGraph(attributePaths = "reasons")`, que es un fetch de colección.
Hibernate no puede aplicar el límite en SQL sin arriesgar un resultado mal recortado, así que trae
**todas** las filas de `llm_analysis` del expediente con **todos** sus motivos y descarta el resto
en memoria.

Hoy no duele: `llm_analysis` es append-only con una fila por corrida, o sea una a tres por
expediente. Pero crece con cada reclasificación y cada reintento manual, y el expediente que más se
reclasifica es justo el que más veces se abre. Además el warning salta en **cada poll** del frontend
mientras el expediente está en `PENDING_CLASSIFICATION`, y ese ruido constante es el que hace que
nadie mire los logs cuando aparece algo real.

Se detectó mirando los logs del smoke test de coberturas (01/09): tres warnings por poll, cada
veinte segundos.

**Notas técnicas**
Es una sola consulta, con dos llamadores en
`ClassificationResultsService` (líneas ~191 y ~236). El arreglo estándar es partirla en dos: una
consulta liviana que resuelva el id de la última corrida (`select max(id) ... where case_id = ?`, o
un `findFirst` sin `@EntityGraph`, que sí puede limitar en SQL), y después traer esa fila con el
grafo por id. También sirve un `@Query` con subconsulta.

`RiskAnalysisRepository.findFirstByCaseIdOrderByIdDesc` tiene la misma forma **pero no el
`@EntityGraph`**, así que no sufre el problema — no hace falta tocarla, y conviene no hacerlo para
que el diff quede acotado a la consulta que sí falla.

Ojo con no cambiar el orden: tiene que seguir siendo la fila de **id más alto**, que es la corrida
más reciente. `ClassificationResultsService` ya depende de eso — al reclasificar, la tabla es
append-only y la anterior queda debajo.

---

## H0036 · Un rules-service caído deja entrar denuncias sin verificar y nadie las vuelve a mirar

**Como** analista de siniestros
**quiero** que una denuncia que entró sin poder verificar su documentación quede marcada y se
vuelva a verificar sola
**para** que una caída de rules-service no me deje expedientes que parecen completos y no lo están.

**Criterios de aceptación**
- Un alta que no pudo leer la agenda documental queda registrada como **entrada sin verificar**,
  con la marca persistida en el expediente (no solo en el log).
- Un proceso reintenta la verificación de esos expedientes cuando rules-service vuelve a responder.
- Si al reintentar faltaba documentación obligatoria, el expediente pasa a `AWAITING_DOCUMENTATION`
  y se le avisa al asegurado qué falta — el mismo aviso que ya existe para los faltantes.
- Si no faltaba nada, se limpia la marca y el expediente sigue su curso normal, sin rastro para el
  analista.
- El expediente **nunca** se rechaza por esto: la caída es nuestra, no del asegurado.
- El reintento es idempotente: correrlo dos veces sobre el mismo expediente no duplica avisos ni
  transiciones.

**Por qué importa**
Desde el 02/09 `cases-service` exige la agenda documental en el alta
(`CaseServiceImpl.assertRequiredDocumentsPresent`), y el wizard hace lo mismo del lado del
asegurado. Las dos verificaciones dependen de que rules-service conteste. Cuando **no** contesta,
las dos dejan pasar la denuncia a propósito: dejar al asegurado afuera porque un servicio nuestro
está caído sería peor que tomar el caso y verificarlo después.

El problema es que ese "después" hoy no existe. La denuncia entra, el expediente queda igual que
uno verificado, y nadie vuelve sobre él. En el mejor caso el gate de documentación faltante del
motor lo agarra en la clasificación; en el peor —si esa corrida también falla o la agenda cambia—
el expediente llega al analista aparentando estar completo.

Distinguir los dos casos ya está hecho en las tres capas (agenda vacía = "no pide documentos";
agenda ilegible = "no sabemos"), así que lo que falta es solo persistir la segunda y retomarla.

**Notas técnicas**
`RulesServiceClient.requiredDocumentTypes` ya devuelve `null` cuando no pudo leer, distinto de la
lista vacía. El llamador es `CaseServiceImpl.assertRequiredDocumentsPresent`, que hoy hace
`return` en ese caso — ahí va la marca.

Encaja con lo que el módulo ya hace: la clasificación es asincrónica con reintento
(`ClassificationRefreshScheduler`), y `AWAITING_DOCUMENTATION` no es exclusivo de este camino — un
expediente también cae ahí por documentación rechazada. O sea que el estado y el patrón de
scheduler ya existen; falta esta causa.

Registrado en el doc de gaps como §13, punto 2.
