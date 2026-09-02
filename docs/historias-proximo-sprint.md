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
