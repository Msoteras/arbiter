# Handoff — Gemma vs Qwen, y por qué la clasificación tarda dos horas

**Fecha:** 20/08/2026, noche · **Rama:** `feature/investigacion-modelos` (sin commitear, sin pushear)
**Card:** Sprint 8 · "Investigar floci, gemma y kimi3" (#83)

> **Las tres líneas que importan.** Gemma 4 **alucina** el contenido de los documentos y queda
> descartada. La lentitud de Qwen **no era del modelo sino nuestra**: el KV cache reservaba 4,6 GB
> por un `num_ctx` diez veces más grande que lo que el pedido usa, y bajarlo multiplicó la
> velocidad por 13. Y apareció un defecto de prompt, independiente del modelo, que mete el
> razonamiento del LLM adentro de la transcripción del acta.

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

## 8 · Expedientes creados hoy (y un hueco del producto)

| # | Modelo | Qué pasó |
|---|---|---|
| **33** | `gemma4:12b-it-qat` | Cortado a los 24 min, sin terminar el 1er documento |
| **34** | `qwen3-vl:8b-instruct` · 32K | Cargado **desde el wizard del front**; cortado |
| **35** | `qwen3-vl:8b-instruct` · 8K | Cortado al apagar; llegó a 5.558 chars |

Los tres son réplicas del #29: mismo payload y **los mismos 4 PDFs byte por byte**, bajados de
`arbiter_bbva.case_documents` (ids 18–21). **El #29 no se tocó.**

Los tres quedaron colgados en `PENDING_CLASSIFICATION`. Conviene borrarlos o dejarlos identificados
para no ensuciar la bandeja del analista.

> ### Hueco encontrado: un expediente puede quedar en el limbo
>
> Si `classification-service` se cae a mitad de una clasificación, el expediente queda en
> **"Pendiente de clasificación" sin forma de destrabarlo desde el front**: el botón de relanzar
> solo aparece cuando el estado es *fallido*, y el `ClassificationRefreshScheduler` tarda 180
> minutos (540 intentos × 20 s) en marcarlo así. Además el scheduler solo **consulta** el resultado
> (`GET /api/v1/claims/{id}`), no re-dispara la clasificación, así que la tarea async muerta no
> revive sola. Es distinto del `CLASSIFICATION_FAILED` ya conocido: acá ni siquiera llega a fallar.
> **Vale una card.**

---

## 9 · Estado de la rama

`feature/investigacion-modelos`, creada desde `develop`, **al día con los 36 commits de Mar**
(fast-forward limpio, sin merge commit, cero conflictos — ella tocó front y reglas, nada de lo mío).
Compila con JDK 21.

**Sin commitear y sin pushear.** 29 archivos en el índice:

| Qué | Archivos |
|---|---|
| Fixtures de Flor traídos por path | 26 bajo `docs/postman/test-docs/` |
| Doc de investigación (corregido, ver abajo) | `docs/investigacion-floci-gemma-kimi.md` |
| Banco de pruebas de modelos | `classification-service/src/test/.../DocumentExtractionBenchmark.java` |
| `ollama-init` lee `${OLLAMA_MODEL}` | `docker-compose.yml`, `docker-compose.railway.yml` (1 línea c/u) |

**Cero archivos bajo `src/main`.** El único `.java` está en `src/test`, detrás de
`@EnabledIfEnvironmentVariable(ARBITER_BENCH)`, y no entra al contenedor.

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

## 10 · Qué hacer mañana, en orden

Los tres primeros tocan `src/main`, así que **necesitan el OK de Fede**.

1. **Separar el `num_ctx` de extracción del de clasificación.** El cambio de mayor impacto medido
   hoy (13× de velocidad) y el único que ataca de frente el RNF de los 10 minutos. Hoy es un solo
   valor en `OllamaClient`; hacen falta dos. Ver el ojo de §3: bajarlo global a secas rompería la
   clasificación.

2. **Probar 100 DPI en vez de 150.** `OllamaDocumentAnalyzer` rasteriza a 150 DPI (1240×1753), y de
   ahí sale la mayoría de los 3.370 tokens del prompt. Bajar a 100 (827×1169) recorta el 56% de los
   píxeles. Es una constante. **Criterio de aceptación:** que el IMEI de 15 dígitos y el nº de
   actuación sigan saliendo exactos — si fallan los dígitos, se descarta con evidencia.

3. **Arreglar el prompt de extracción** para que no razone dentro de `transcription` (§4).

4. **Correr el benchmark entero** con la configuración ya corregida, y volcar la tabla al doc de
   investigación y al paper de CONAIISI.

5. **Decidir qué hacer con la rama:** hay 29 archivos sin commitear y sin pushear. Los fixtures de
   Flor probablemente convenga separarlos —son de ella— del resto.

6. **Limpieza:** borrar los expedientes 33, 34 y 35 de Railway.

### Lo que quedó sin tocar de la card #83

**Floci** (el emulador de AWS para el `S3Adapter`) y **Kimi K2.6** como techo de calidad medido. Ver
`docs/investigacion-floci-gemma-kimi.md` §1 y §3. Ninguno de los dos toca el problema de RAM, que
era lo urgente.
