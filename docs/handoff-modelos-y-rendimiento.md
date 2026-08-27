# Handoff — Gemma vs Qwen, y por qué la clasificación tarda dos horas

**Fecha:** 20/08/2026 (noche) y 21/08 · **Rama:** `feature/investigacion-modelos`
**Card:** Sprint 8 · "Investigar floci, gemma y kimi3" (#83)

> **Las cuatro líneas que importan.** Gemma 4 **alucina** el contenido de los documentos y queda
> descartada. La lentitud de Qwen **no era del modelo sino nuestra**: el KV cache reservaba 4,6 GB
> por un `num_ctx` diez veces más grande que lo que el pedido usa, y bajarlo multiplicó la
> velocidad por 13. El prompt metía el razonamiento del LLM adentro de la transcripción del acta —
> **corregido y verificado en vivo**. Y la leyenda "documento simulado" de los fixtures **contamina
> las clasificaciones**: con ella, 3 de 8 motivos hablaban del cartel y el caso se rechazaba; sin
> ella, cero motivos y el mismo pipeline aprueba.

> ### ⚠️ Lo que queda abierto para el 22/08
> El prompt **v5** corrige una regresión que introdujo el v4: al pedirle al modelo que no razonara,
> dejó de completar `fields` (§5). **Está sin verificar en vivo.** El criterio que puso Fede es
> binario: *"se tiene que popular todo bien, si no volvemos al v3"*. Cómo verificarlo, en §5.

---

## 1 · Cómo retomar mañana

Todo está apagado. Para volver al punto donde quedamos:

```bash
# 1. Levantar el stack (Ollama con el contexto corregido)
OLLAMA_MODEL=qwen3-vl:8b-instruct OLLAMA_NUM_CTX=8192 \
  docker compose -p arbiter-railway \
  -f docker-compose.railway.yml -f docker-compose.override.yml \
  up -d ollama clip-embedding auth-service cases-service classification-service rules-service reports-service
```

**Nombrar siempre los dos archivos `-f`**: al pasar `-f` explícito, Compose deja de cargar
`docker-compose.override.yml` solo, y sin él vuelven los `mem_limit` de la máquina de Aylén
(20,75 GB) que en 16 GB hacen que el kernel mate `llama-server`.

Los dos modelos ya están bajados en el volumen `arbiter-railway_ollama_data` — **no hay que
descargar nada**, alternar es solo la variable:

| Modelo | Tag | Tamaño |
|---|---|---|
| Qwen3-VL 8B instruct | `qwen3-vl:8b-instruct` | 6,1 GB |
| Gemma 4 12B QAT | `gemma4:12b-it-qat` | 7,2 GB |

El front se levanta aparte (`npm --prefix arbiter-frontend start`, puerto 4200) y hay un
`.claude/launch.json` ya configurado.

---

## 2 · La medición, en una tabla

Todo sobre **el mismo documento** (`denuncia_policial_fast_track.pdf`, 6.473 bytes, 1 página), con
el mismo prompt (`extraccion-documento-v3`), el mismo schema y el mismo rasterizado a 150 DPI. Lo
único que cambió entre corridas fue el modelo y el `num_ctx`.

| | Velocidad | Fidelidad de la transcripción |
|---|---|---|
| **Gemma 4 12B QAT** · 32K | ~1,0 char/s | ❌ **alucina** |
| **Qwen3-VL 8B** · 32K | 0,24 char/s | ✅ palabra por palabra |
| **Qwen3-VL 8B** · **8K** | **3,2 char/s** | ✅ palabra por palabra |

### Por qué Gemma queda descartada

No es un problema de OCR ni de velocidad: **inventa el relato**. Contraste literal contra el acta:

| El acta dice | Gemma transcribió |
|---|---|
| las **19:25** horas | las **22.35** horas |
| Av. Rivadavia al **2800** | Av. Rivadavia al **2000** |
| calle **Pasco** | Paseo **Pasicu** |
| abordada por un masculino **en bicicleta** | un hombre **con mascarilla y delincuente** |
| mediante **un tirón le arrebató** | **robo mediante el uso de arma de fuego** |
| **"no sufrió lesiones ni fue amenazada con arma alguna"** | **"uso de arma de fuego"** |
| — | *"dándole las indicaciones de entregarle su celular"* ← inventado |

La anteúltima es la que cierra la discusión: el acta dice **explícitamente que no hubo arma** y el
modelo escribió que hubo arma de fuego. En un expediente de seguros eso no es un error de OCR — es
un cambio en la calificación del hecho (arrebato vs robo con arma) que altera la cobertura y que el
analista leería como si lo dijera el papel.

**Esto no lo arregla más RAM ni más contexto.** Si alguien quiere reabrir la discusión, valdría
probar `gemma4:12b` a secas (q4_K_M, 7,6 GB) por si la degradación viene de la cuantización QAT —
pero el patrón de "completar con un relato plausible" no huele a problema de cuantización.

---

## 3 · El hallazgo importante: el KV cache

**Esto es lo que hay que llevarse de la jornada**, y no tiene nada que ver con qué modelo se use.

Ollama imprime su reparto de memoria al cargar. Con `num_ctx=32768`:

```
| memory breakdown [MiB] |  self   model   context   compute |
|   - Host               |  6559 =  1583 +    4608 +     368 |
|   - CPU_REPACK         |  3204 =  3204                     |
```

**4.608 MiB de KV cache.** Y el pedido real, en la línea de al lado:

```
slot update_slots: new prompt, n_ctx_slot = 32768, task.n_tokens = 3370
```

**3.370 tokens.** Reservábamos casi diez veces lo necesario, y el KV cache se reserva por `num_ctx`
completo, no por lo que se usa: 4,6 GB apartados para nada.

Con `num_ctx=8192` el cache baja a **1.152 MiB** — 3,4 GB liberados — y Ollama pasa de **99,75% a
89%** de su techo de 10 GB. Esa diferencia es la que decide si el proceso swapea a disco o no
(el `.wslconfig` de Fede tiene `swap=8GB`), y de ahí sale el salto de **0,24 a 3,2 char/s**.

**Esto explica la brecha entre máquinas** que el handoff del 18/08 no explicaba: en la de Aylén
(32 GB, WSL en 24) el KV cache entra cómodo y un PDF se lee en ~40 s; en la de Fede (16 GB, WSL en
13) no entra, y el mismo PDF tarda minutos.

### Ojo con la decisión #2

CLAUDE.md fija la ventana en 32.768 tokens y **eso sigue estando bien para la clasificación**
(prompt estimado 6–15k). El problema es que **extracción y clasificación comparten un solo
`num_ctx`** (`arbiter.ollama.num-ctx`, inyectado en el constructor de `OllamaClient`), y la
extracción de un documento usa 3.370. Lo correcto es separarlos: contexto chico para leer
documentos, grande para clasificar. **Bajar el valor global a 8192 a secas achicaría también el de
clasificación, que sí lo necesita** — no hacerlo sin separar los dos primero.

---

## 4 · El otro defecto encontrado: el modelo razona adentro de la transcripción

Independiente del modelo y del contexto. Corriendo Qwen, después de transcribir el acta correctamente,
siguió de largo escribiendo su propio razonamiento **dentro del campo `transcription`**:

```
"...Por lo tanto, `affectedParty` es \"TITULAR\".
En resumen:
- documentDate: 2026-08-10 (porque está escrito \"10/08/2026\"...)
- amount: null (no se menciona ningún monto ni valor monetario)
- itemDescription: Samsung Galaxy A56..."
```

Las comillas escapadas (`\"`) confirman que está adentro del string. Son ~3.000 caracteres de
deliberación sobre cómo completar el schema, que se guardarían como si fueran el texto del acta y
que el analista leería como contenido del documento.

**Por qué pasa:** el schema emite los campos en orden (`visualFindings`, `transcription`, `fields`),
y el modelo delibera sobre `fields` antes de llegar a ese campo — así que la deliberación cae en el
único campo de texto libre que tiene abierto. El prompt le dice "acá va solo lo que el documento
dice", pero no le prohíbe razonar en voz alta.

**Riesgo concreto:** `num_predict` está en 4.096 tokens. Si la deliberación sigue, la respuesta se
corta a mitad de un string JSON, el parseo falla y el documento degrada a *"No se pudo extraer
contenido del documento adjunto."* — **probablemente sea por esto que el expediente #29 no tiene ni
una fila en `document_analysis`**.

### Corregido — y el arreglo tuvo un daño colateral que costó encontrar (21/08)

El **v4** agregó tres bloques al prompt (no borró nada del v3) y el schema pasó a `LinkedHashMap`
con orden fijo `transcription → fields → visualFindings`. Dos cosas hacían falta a la vez: el orden
le saca al modelo el string abierto donde volcaba la deliberación, y el prompt se lo dice explícito.

**Funcionó:** el #33 pasó de 5.558 caracteres sin cerrar a **2.884 que cierran**, con la
transcripción terminando en la última línea del papel. Verificado en los 4 documentos del #33 y en
los 4 del #20, en dos tenants distintos.

**Pero rompió otra cosa.** Esta frase que quedó en el v4:

> *"nada de listar `documentDate`, `amount`, `imei` o `affectedParty` con su justificación"*

estaba escrita pensando "no los expliques en prosa". El modelo la leyó como **"no los emitas"** — y
como `fields` no era obligatorio en el schema, tenía permiso para omitirlo. El corte es exacto y se
ve en los timestamps (el v4 entró al contenedor a las 16:44 UTC):

| Caso | Extraído | Prompt | `fields` |
|---|---|---|---|
| 36 | 15:43 UTC | v3 | ✅ |
| 38 | 16:18 UTC | v3 | ✅ los 4 documentos |
| 33 | 18:20 UTC | **v4** | ❌ 3 de 4 vacíos |
| 20 | 21:xx UTC | **v4** | ❌ los 4 vacíos |

**Por qué importa más que el problema original:** sin `fields`,
`DocumentInconsistencyEvaluator` (D4b) no tiene con qué trabajar. El IMEI contra el del bien
asegurado, el importe contra el monto reclamado, la fecha del acta contra la declarada: nada de eso
se compara. Y **no falla ni avisa** — la transcripción se ve perfecta mientras esa capa entera está
muerta. Se cambió un defecto cosmético por uno funcional.

El **v5** lo corrige por los dos lados: el prompt ahora dice *"Que no haya que justificar no
significa que haya que omitir"* y muestra el `fields` vacío como segundo contraejemplo; y `fields`
pasa a estar en la lista de `required` del schema (sus claves siguen nulleables, para que "el papel
no lo dice" siga siendo expresable). Tres tests cubren la regresión, uno de ellos sobre el texto del
prompt — inusual, pero la regresión estuvo en una frase, no en el código.

> **Sin verificar en vivo.** Cómo hacerlo: correr un caso y mirar
> ```sql
> SELECT cd.type, d.document_date, d.amount, d.imei, d.item_description, d.affected_party
> FROM arbiter_bbva.document_analysis d
> JOIN arbiter_bbva.case_documents cd ON cd.id = d.case_document_id
> WHERE cd.case_id = <id>;
> ```
> La referencia es el **caso 38** (corrió con v3): los 4 documentos con `document_date` e `imei`, y
> la factura con `amount = 589999.00`. Si el v5 no llega a eso, **volver al v3** — decisión de Fede.

---

## 4bis · La leyenda de prueba contamina la clasificación (verificado 21/08)

Es el hallazgo más accionable de la jornada y valida el set `sinMarca/` de Flor.

Los fixtures de `conMarcaDePrueba/` llevan impreso al pie: *"Documento simulado con fines de prueba
— sistema Arbiter (UTN FRBA, DDSI K5054, grupo 5303). No constituye un comprobante ni una constancia
real."* **El modelo lo lee** (la página se rasteriza entera) y lo usa como argumento.

| | #33 · `conMarcaDePrueba` | #20 · `sinMarca` |
|---|---|---|
| Motivos totales | 8 | 7 |
| **Sobre "documento simulado"** | **3** | **0** |
| Veredicto | `LLM_NO_RECOMIENDA_APROBAR` | `LLM_RECOMIENDA_APROBAR` |

Con el cartel, el modelo llegó a escribir *"esto implica que **todos** los documentos adjuntos son
falsos"* y a descartar por contagio una factura que estaba impecable. Sin el cartel, los siete
motivos son análisis real: cruza el relato contra el acta y la constancia de bloqueo, verifica que
el número de serie de la factura sea el del equipo denunciado, chequea que el bloqueo remoto sea del
mismo día.

**Consecuencia para cualquier medición futura:** comparar modelos con `conMarcaDePrueba/` mide cuán
bien leen un disclaimer, no cuán bien analizan un siniestro. Los dos van a rechazar. **Usar siempre
`sinMarca/` para medir calidad.**

Ojo con el detalle que ya se aclaró dos veces en el equipo: los **metadatos** del PDF (`/Keywords`,
`/Producer`, `/Subject`) están en las **dos** variantes y el modelo **no los ve** — viven en la
estructura del archivo, no en los píxeles. Lo único que cambia entre variantes es la leyenda
**visible**. El diseño de Flor es correcto: cubre el requisito legal sin ensuciar la medición.

---

## 4ter · Dos gaps de cobertura encontrados de paso (21/08)

**A · El hecho generador no elige la cobertura.** La BD Aseguradora modela varias coberturas por
póliza (Robo $1.300.000, Hurto $650.000, cada una con su franquicia y sus límites); la copia local
`arbiter_bbva.policy` las aplana a **una sola** (`coverage_id`, `sum_insured`). Verificado: **las 11
pólizas de BBVA tienen `coverage_id = 1`**. Resultado medido en el #39 — un **hurto** de $620.000 se
evaluó contra la suma de **robo** y pasó Fast Track con 47,7%, cuando contra su cobertura real es el
**95,4%** y debía frenarse. Se pierden además la franquicia (20% vs 10%), el tope de eventos
anuales (1 vs 2) y `claim_exhausts_coverage`.

No se sabe si es simplificación deliberada o gap: `Policy.coverage` está en singular, así que alguien
lo modeló así. **Aylén es la indicada** para decir si se decidió o se pasó.

**B · Tecnología Portátil no tiene criterios de Fast Track configurados.** El #20 no fue bloqueado
por el monto sino porque *"No hay criterios de Fast Track configurados para Tecnología
Portátil/Hurto"*. El default es seguro (no fast-trackea), pero significa que hoy ese ramo entero no
puede fast-trackear **por falta de configuración, no por decisión**.

**C · El Fast Track nunca cruza el relato contra los documentos.** El #39 tenía una contradicción
declarada (el formulario decía "parada de colectivo en Av. Rivadavia 2800", el acta narra un hurto
en el subte, Línea B) y **nadie la miró**: con Fast Track se lee un solo documento y las 6 reglas que
corren son temporales y de póliza (vigencia, carencia, plazos, eventos, antecedentes), ninguna
compara relato contra papel. Esa comparación la hace el LLM, en la mitad que el gate saltea.
Pregunta de diseño para el equipo: ¿debería el gate verificar coherencia documental antes de
habilitar la vía rápida? La decisión #5 lo cubre en parte —siempre hay un analista— pero el analista
ve un caso marcado como "todo en orden".

---

## 5 · La referencia del #29 estaba mal contada

Circulaba que la clasificación del #29 tardó 60 minutos. **No: tardó 2 horas y 1 minuto.**

| | |
|---|---|
| Denuncia recibida (`reported_at`) | 18/08 20:15:38 |
| Clasificación terminada (`llm_analysis.analyzed_at`) | 18/08 22:16:56 |
| **Total** | **2 h 01 min** |
| De eso, `latency_ms` de la clasificación final | 60,0 min |

Los 3.601.368 ms que guarda `llm_analysis.latency_ms` son **solo la llamada final al LLM**; las
cuatro extracciones de visión van aparte. Contra el RNF de <10 min de la decisión #4, estábamos
**12× por encima**, no 6×.

Resultado del #29 con Qwen: `LLM_NO_RECOMIENDA_APROBAR`, confianza 0,950, prompt `classification-v4`.

### Y ese resultado está contaminado

**Dos de los seis motivos del rechazo son la leyenda de prueba de los fixtures:**

> *"Los documentos adjuntos están claramente marcados como simulados con fines de prueba... Esto
> genera duda sobre la autenticidad del equipo robado o la titularidad."*

> *"La factura adjunta también está marcada como simulada... entonces toda la cadena documental es
> inconsistente."*

Un tercero arranca con *"El documento simulado muestra que..."*. O sea: **la mitad del rechazo es
ruido del fixture, no análisis del caso.** Es exactamente el problema que ataca el set `veridica/`
de Flor (ver §6).

Los otros motivos sí son legítimos y buenos — detectó que el hecho declarado (16/08, Av. Santa Fe
3200) no coincide con la constancia de última conexión (10/08, Av. Rivadavia 2750). Qwen cruza bien
los documentos.

---

## 6 · Sobre los metadatos de los fixtures — la duda de Fede, resuelta

Circulaba la idea de que los metadatos que Flor pone en los PDF (`/Keywords`, `/Producer`,
`/Subject`) podían hacer que el modelo rebote el documento. **No pueden: el modelo no los ve.**

`OllamaDocumentAnalyzer` rasteriza la página a PNG a 150 DPI y manda la imagen
(`renderImageWithDPI(page, 150)` → base64). Ollama no acepta PDF. Los metadatos viven en la
estructura del archivo, no en los píxeles.

- **El diseño de Flor es correcto:** los metadatos cubren el requisito legal —el archivo se
  identifica como fixture aunque salga del repo— sin ensuciar la medición.
- **Lo que el modelo sí lee es la leyenda visible del pie**, "Documento simulado con fines de
  prueba". Eso se rasteriza y llega a la imagen. Es lo que ella saca en `veridica/`, y §5 muestra
  que la decisión estaba bien fundada.

---

## 7 · El trabajo de Flor, revisado

Sus dos commits del 19/08 (`d3103d3`, `97b42ec`) están **en esta rama**, traídos por path para no
arrastrar el resto de `feature/pruebas-celulares`, que está muy desactualizada — mergearla borraría
peritaje, notificaciones, hash-contraseña y el handoff de Ollama.

Verificado regenerando los fixtures en un temporal:

| | |
|---|---|
| Los dos generadores corren en ambas variantes | ✅ |
| Concordancia de género en el acta masculina | ✅ "DEL DENUNCIANTE", "argentino", "sorprendido", "amenazado" |
| Fugas de "Martina" en el set verídico | ✅ cero |
| Leyenda "simulado": 4/4 en `fraude/`, 0/4 en `veridica/` | ✅ |
| Restos de femenino en los otros PDFs | ✅ falsos positivos ("TecnoService **Argentina**", "la **titularidad**") |

### Dos problemas de datos, ninguno culpa de ella

**A · Roman Castillo (DNI 33.845.219) no existe en el seed.** `InsurerDatabaseAdapter` busca
`WHERE a.documento = ?`, así que **el set `veridica/` no puede dar de alta un expediente** contra la
BD actual, en ninguno de los dos escenarios. No afecta una prueba de extracción (que lee el PDF, no
la base), pero sí el end-to-end.

**B · `POL-TEC-2026-311` no es de Martina, es de Carla Gómez** (35.111.222, Provincia). El
comentario del generador dice "Martina Soteras. Es la única póliza del ramo que existe en la BD" —
quedó viejo: hoy hay 5 pólizas de Tecnología Portátil, y la de Martina en BBVA es `POL-TEC-2026-050`.
Viene de antes de los commits del 19/08.

**De los 4 sets, solo `fraude/fast-track` cierra hoy contra la base** (Martina + POL-CEL-2026-042).

### Vigencia

Los fixtures caducan (§2 del README de Flor). Los del repo tienen el hecho el 18/08:

| Escenario | Plazo | Vence |
|---|---|---|
| Celulares · Fast Track | 72 hs | **21/08 19:25** |
| Tecnología Portátil | 96 hs | 22/08 22:10 |

**Si mañana el fast-track se comporta raro, lo primero a descartar es que el set esté vencido** —
regenerarlo con `node docs/postman/test-docs/generar-fixtures.js`.

---

## 8 · Expedientes de prueba y tiempos medidos

| # | Tenant | Config | Resultado | Total |
|---|---|---|---|---|
| **33** | BBVA | Gemma, 32K | cortado a los 24 min sin cerrar 1 documento | — |
| **33** | BBVA | Qwen 8K, sin CLIP, **v4** | `LLM_NO_RECOMIENDA_APROBAR` · 0,98 | **1 h 12 min** |
| **39** | BBVA | Qwen 8K, **v4** | `FAST_TRACK` (mal otorgado, ver §4ter A) | 23,6 min |
| **20** | **Provincia** | Qwen 8K, con CLIP, **v4** | `LLM_RECOMIENDA_APROBAR` · 0,98 | **2 h 17 min** |

Desglose del #33 (el más comparable con el #29): extracción de 4 documentos **49,2 min**
(17,1 + 12,0 + 9,6 + 10,5), clasificación final **72,3 min**. Contra las **2 h 01 min** del #29:
**−40%**. Toda la mejora vino de la extracción; la clasificación final incluso tardó más (72 vs 60),
porque su prompt es el más pesado del flujo (4.678 tokens contra 3.370 de una extracción) y ahí el
`num_ctx` ayuda menos.

**El #20 tardó más (2 h 17) por dos razones**: un documento más y CLIP compitiendo por CPU.

### Dos mediciones sueltas que sirven

- **CLIP compitiendo cuesta ~5×.** Con el sidecar prendido, la generación iba a 0,28 char/s; sin él,
  1,47 char/s en el mismo punto de la curva. Se comía un 38% de CPU sin aportar nada a un caso sin
  fotos. **Pero `classification-service` depende de él con `condition: service_healthy`**, así que
  para apagarlo hay que levantar con `--no-deps`. Para casos sin imágenes es una dependencia que
  cuesta 640 MB y CPU al pedo.
- **El costo de una inferencia lo manda el texto a generar, no el peso del archivo.** La foto del
  MacBook (247 KB) tardó 7,3 min y produjo 51 caracteres; el acta (6 KB) tardó 22,6 min y produjo
  2.818. Los casos con foto no son más caros por la imagen.

Todos los casos de prueba son réplicas o variantes del #29. **El #29 no se tocó.** Los expedientes
**34 y 35 se borraron** con sus dependencias (las 8 FK son `CASCADE`) más los `policy_snapshot`
22 y 23, que van al revés y quedaban huérfanos. Quedan el 33, el 39 y el 20 para limpiar.

> ### Hueco confirmado: un expediente queda en el limbo, y no hay salida por la API
>
> Si `classification-service` se cae a mitad de una clasificación, el expediente queda en
> `PENDING_CLASSIFICATION` **sin forma de destrabarlo por ninguna vía**:
>
> - `POST /{id}/retry-classification` exige `CLASSIFICATION_FAILED` → **409**
> - `POST /{id}/documents` (reclasificar) → `PENDING_CLASSIFICATION → PENDING_CLASSIFICATION` es
>   transición inválida → **409**
> - El `ClassificationRefreshScheduler` solo **consulta** el resultado, no re-dispara nada
>
> Queda esperar 180 minutos (540 intentos × 20 s) a que el scheduler lo marque fallido. Pasó **tres
> veces el 21/08**; se destrabó moviéndolo a `CLASSIFICATION_FAILED` por SQL con su entrada de
> historial, pero eso no es algo que un analista pueda hacer. **Arreglo posible:** permitir
> `PENDING → PENDING` en la máquina de estados, o que `retry-classification` acepte desde `PENDING`.
> **Vale una card.**

---

## 8bis · Dos bugs del wizard de denuncia (21/08)

Encontrados cargando el caso de Roman por el front. Los dos dejan al usuario trabado y el orden de
carga que los dispara es el natural.

**1 · Cargar la fecha de la denuncia policial antes que la hora rompe el formulario.** La hora queda
en 00:00, salta *"La denuncia policial no puede ser anterior al siniestro"*, y **los campos
siguientes desaparecen del DOM** — incluido el propio campo de hora que hace falta para corregirlo.
Verificado: el formulario pasa de 13 inputs a 3.

**2 · Ese error se filtra al paso 1 y ahí deshabilita "Continuar".** El usuario ve el mensaje sobre
la denuncia policial en la pantalla de "Tu póliza", con el botón gris y ningún campo de fecha a la
vista. Única salida: cancelar y empezar de nuevo.

Se esquiva cargando las horas antes que las fechas.

---

## 9 · Estado de la rama

`feature/investigacion-modelos`, al día con `develop` (incluido el PR #48 que mergeó la rama de
Flor con los fixtures reorganizados y la migración de Roman). Compila con JDK 21.

**Commiteado y pusheado:** `ebd936f` — *"fix(clasificación): que el modelo no escriba su razonamiento
adentro de la transcripción"*. 8 archivos: el prompt v4, el schema ordenado, el test del orden, el
banco de pruebas, los dos compose y los dos docs.

**Sin commitear** (lo del 21/08 a la noche, §4 y §5):

| Qué | Archivo |
|---|---|
| Prompt v5 — arregla la regresión de `fields` | `prompts/extraccion-documento-v5.md` |
| `fields` obligatorio en el schema + referencia al v5 | `OllamaDocumentAnalyzer.java` |
| 2 tests de regresión (schema y texto del prompt) | `OllamaDocumentAnalyzerTest.java` |
| Referencia al v5 | `DocumentExtractionBenchmark.java` |
| Este handoff, actualizado | `docs/handoff-modelos-y-rendimiento.md` |

Los prompts **v3 y v4 quedan en el repo**: la versión viaja en el log de auditoría de cada
clasificación (D7 / SSN 2/2023), así que pisarlos rompería la trazabilidad de lo ya clasificado.

**Ojo con el orden de rebuild.** El 21/08 se perdió una corrida entera por esto: se reconstruyó solo
`classification-service` y `rules-service` quedó con una imagen anterior al merge de develop, sin el
endpoint `/rules/internal/fraud-record-rule` que agregó Mar. Daba **404** y el síntoma aparecía como
un fallo de clasificación. **Después de traer develop, `docker compose build` de todos los
servicios**, no solo del que uno tocó.

### El banco de pruebas

`DocumentExtractionBenchmark` corre el `OllamaDocumentAnalyzer` real (mismo prompt, mismo schema,
mismo rasterizado) y solo cambia el modelo entre corridas. Puntúa el OCR **objetivamente**: saca los
*tokens duros* del PDF con PDFBox (IMEI, nº de actuación, importes, series) y cuenta cuántos
transcribió el modelo. Descarga los pesos entre modelo y modelo para no tener dos residentes a la vez.

```bash
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.11"
$env:ARBITER_BENCH = "1"
$env:ARBITER_BENCH_MODELS = "qwen3-vl:8b-instruct,gemma4:12b-it-qat"
mvn -q -pl classification-service test -Dtest=DocumentExtractionBenchmark
```

Escribe `docs/comparativa-modelos.md`. **No se llegó a correr entero** — sirve para la tabla
definitiva del paper sin tener que mirar logs a mano.

### Correcciones al doc de investigación

Verificadas contra las fuentes:

- **Existen tags QAT** que la tabla original no listaba: `gemma4:12b-it-qat` pesa **7,2 GB** (no
  7,6), `e4b-it-qat` 6,1 GB, `e2b-it-qat` 4,3 GB.
- **Licencia confirmada:** `google/gemma-4-12b-it` declara `apache-2.0` → el argumento de la
  decisión #1 sobrevive a un cambio de modelo.
- **No hace falta un tag `-instruct`:** en Gemma 4 el razonamiento es opt-in (token `<|think|>` en
  el system prompt), así que el desastre de los 27 minutos con 0 chars de Qwen thinking no se repite.

---

## 10 · Qué hacer el 22/08, en orden

**1 · Verificar el v5 en vivo. Bloquea todo lo demás.** El criterio de Fede es binario: si `fields`
no se puebla, **volver al v3**. La consulta y la referencia (caso 38) están al final de §4. Un caso
de `sinMarca/celulares/robo` alcanza — se resuelve con una sola extracción si fast-trackea, y con
eso ya se ve si los campos vienen.

**2 · Separar el `num_ctx` de extracción del de clasificación.** El cambio de mayor impacto medido
(13×) y el único que ataca de frente el RNF de los 10 minutos. Hoy es un solo valor en
`OllamaClient`; hacen falta dos. Ver el ojo de §3 — aunque la medición del 21/08 lo relativiza: el
prompt de clasificación resultó ser de **4.678 tokens**, no los 6–15k que estima la decisión #2, así
que 8K le alcanza con margen. Separarlos sigue siendo lo correcto, pero es menos urgente de lo que
parecía.

**3 · Decidir qué pasa con Ollama, ahora que Mar tiene Vertex andando.** Ella clasificó un fast
track **en segundos** contra nuestras horas. La rama es `feature/config-vertex` y tiene su handoff.
Lo de acá que le sirve **tal cual, sin adaptar**: el prompt v5 y el orden del schema son
independientes del modelo — si Gemini también está metiendo razonamiento en `transcription` (Mar
mencionó "algo que no sé si hizo bien" en la denuncia policial), se le arregla cambiando la
referencia al prompt.

**4 · Levantar las cards de los hallazgos.** Por orden de impacto: la cobertura que no depende del
hecho generador (§4ter A), el expediente en el limbo (§8), los dos bugs del wizard (§8bis), los
criterios de Fast Track faltantes en Tecnología (§4ter B).

**5 · Correr el benchmark entero** con `sinMarca/`, ya con el v5 verificado. Es lo que cierra la
card #83 con una tabla en vez de una opinión, y da material para el paper.

**6 · Limpieza:** borrar los expedientes 33, 39 (BBVA) y 20 (Provincia) de Railway.

### Lo que quedó sin tocar de la card #83

**Floci** (el emulador de AWS para el `S3Adapter`) y **Kimi K2.6** como techo de calidad medido. Ver
`docs/investigacion-floci-gemma-kimi.md` §1 y §3. Ninguno de los dos toca el problema de RAM, que
era lo urgente.

### Y una decisión de arquitectura que hay que escribir

Fede decidió el 21/08 que **para el proyecto académico se puede usar GCP/Vertex**, con el argumento
de que los fixtures son sintéticos y de que si esto pasara a producción la inferencia volvería a
infraestructura propia. Eso **relaja la decisión #1** y conviene que quede escrito como decisión con
su justificación, no que se cuele por la ventana. El punto a favor: los fixtures no llevan ni un dato
real, así que no hay exposición de datos de asegurados.

Y un dato para esa conversación: **el hardware del equipo es heterogéneo** (Flor tiene una RTX, Fede
una RX 580, las demás probablemente integradas), así que cualquier camino que dependa de la GPU local
deja gente afuera y no sirve para la demo. Si en algún momento hay que medir Ollama sobre GPU, **Flor
es la que tiene el hardware** para dar el número bueno.
