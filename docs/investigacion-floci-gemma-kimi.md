# Investigación — Floci, Gemma 4 y Kimi K3

**Card:** Sprint 8 · "Investigar floci, gemma y kimi3" (#83) · Fede / Valen / Aylu
**Fecha:** 20/08/2026

> **Resumen en una línea.** De las tres, **una sola es un modelo que nos sirve hoy (Gemma 4)**;
> Floci no es un modelo sino un emulador local de AWS (nos toca por la decisión #15: S3 para
> adjuntos), y Kimi K3 es un modelo de 2.8 **billones** de parámetros que no podemos correr ni
> pagar — nos sirve como referencia de calidad, no como opción de despliegue.

---

## Punto de partida (por qué existe esta card)

Hoy corremos `qwen3-vl:8b-instruct` en Ollama por CPU, dentro de Docker. El handoff del 18/08
(`docs/handoff-ollama-cpu-y-scheduler.md`) dejó `mem_limit: 14g` para Ollama solo, calibrado para
una máquina de 32 GB con WSL2 subido a 24 GB. Sobre esa base:

- En una notebook de **16 GB** (la de Fede) esos números no entran: 14 GB para Ollama + 2 GB del
  sidecar de CLIP + ~5 GB de las cinco JVM es más RAM de la que hay.
- La app va a terminar en **Railway** (hoy Aylu tiene el plan Hobby de **US$5/mes**) y la BD se
  migra a **Supabase**.

Entonces la pregunta real de la card no es "qué modelo es más lindo" sino **dónde corre la
inferencia y con qué modelo entra en el presupuesto de RAM que tenemos**. Las tres herramientas
tocan pedazos distintos de ese problema.

---

## 1 · Floci — emulador local de AWS (no es un modelo)

**Qué es.** Un emulador de servicios AWS que corre local, escrito en Quarkus y compilado nativo con
GraalVM. Es el reemplazo directo (drop-in) de **LocalStack**, con licencia **MIT** y sin edición
"community" recortada ni tokens de registro.

**Números que importan en una máquina con poca RAM:**

| | Floci |
|---|---|
| Imagen Docker | ~90 MB |
| Arranque | ~24 ms |
| Memoria en reposo | ~13 MiB |
| Servicios emulados | ~75 (S3, DynamoDB, Lambda, SQS, SNS, RDS, ECR, EventBridge…) |
| Licencia | MIT |

**Qué nos aporta.** La decisión de arquitectura **#15** dice "Despliegue en Docker sobre
Railway/AWS. RDS para Postgres, **S3 (+ Glacier a 30d, borrado a 180d) para adjuntos**, ECR para
imágenes". Hoy los adjuntos del asegurado se guardan en filesystem/volumen Docker: la parte de S3
está escrita en el documento de arquitectura pero **no implementada**, y para implementarla hay que
elegir entre pagar/abrir una cuenta AWS o emular.

Concretamente sirve para:

1. **Escribir el `S3Adapter` sin cuenta de AWS.** Igual que `Auth0Adapter` o `OllamaAdapter`, el
   adapter habla contra un endpoint configurable: en dev apunta a Floci, en producción a S3 real.
   Cero costo, cero riesgo de subir un adjunto de prueba a un bucket que después alguien olvida.
2. **Tests de integración**: mismo patrón que Testcontainers para PostgreSQL, que ya usamos.
3. **Defender la decisión #15 en la documentación** con algo funcionando, en vez de "iría a S3".

**Qué NO nos aporta.** Nada del problema de RAM del LLM, nada del despliegue en Railway. Es
herramienta de desarrollo y testing. Y ojo con la fidelidad: emula el protocolo, no es AWS — para
el ciclo de vida a Glacier / borrado a 180 días hay que verificar que soporte lifecycle rules antes
de prometerlo.

**Costo de adoptarlo:** bajo. 90 MB de imagen y 13 MiB en reposo no compiten con Ollama por
memoria, que es la restricción real.

---

## 2 · Gemma 4 — la única de las tres que puede reemplazar a Qwen3-VL

**Qué es.** La familia abierta de Google (Gemma 4, marzo–junio 2026). Todas las variantes aceptan
**texto e imagen**, que es requisito nuestro: `OllamaDocumentAnalyzer` rasteriza los PDF adjuntos y
se los manda al modelo como imagen.

| Variante | Parámetros | Contexto | Tamaño en Ollama (q4) | Nota |
|---|---|---|---|---|
| `gemma4:e2b` | 2,3B efectivos | 128K | 7,2 GB | también audio |
| `gemma4:e4b` | 4,5B efectivos | 128K | 9,6 GB | también audio |
| `gemma4:12b` | 11,95B | 256K | **7,6 GB** | "unified", texto+imagen+audio |
| `gemma4:26b-a4b` | 25,2B totales / **3,8B activos** (MoE) | 256K | 18 GB (≈15 GB en Q4_K_M) | el interesante para CPU |
| `gemma4:31b` | 30,7B densos | 256K | 20 GB | fuera de nuestro alcance |

**Por qué el MoE cambia el juego en CPU.** En un modelo *Mixture of Experts* solo se activan
algunos expertos por token: el 26B-A4B mueve **3,8B de parámetros activos** aunque tenga 25B en
disco. Un benchmark público en un server viejo (dos Xeon E5-2690 v2, DDR3, ~US$300) mide **9–10
tok/s con Q4_K_M**, contra "menos de 1 tok/s" que daría un denso de 26B en el mismo fierro. La
contra: necesita **~20 GB de RAM** porque los pesos se cargan enteros aunque no se usen todos.

**Los tags QAT, que la tabla de arriba no muestra.** Además de la cuantización posterior al
entrenamiento (`q4_K_M`, que es lo que sirven los tags cortos), Google publica variantes **QAT**
—*quantization-aware training*, el modelo se entrena ya sabiendo que va a correr en 4 bits— y en
Ollama entran **más chicas que el q4 equivalente**:

| Tag | Tamaño | Contra el tag corto |
|---|---|---|
| `gemma4:e2b-it-qat` | 4,3 GB | −2,9 GB vs `e2b` |
| `gemma4:e4b-it-qat` | 6,1 GB | −3,5 GB vs `e4b` |
| **`gemma4:12b-it-qat`** | **7,2 GB** | −0,4 GB vs `12b` |
| `gemma4:26b-a4b-it-qat` | 16 GB | −2 GB vs `26b-a4b` |

Es la opción por defecto para nosotros: misma familia, menos RAM, y la degradación de calidad es
menor que la de un q4 posterior justamente porque el entrenamiento la contempló.

**Traducido a nuestras máquinas:**

- **16 GB (Fede):** el candidato es **`gemma4:12b-it-qat` (7,2 GB)** — 12B densos por el precio en
  memoria del `e2b`, apenas por encima del `qwen3-vl:8b-instruct` que usamos hoy (6,1 GB), más
  contexto (256K vs los 32K que fijamos) y visión. Si no entra con las cinco JVM al lado,
  `e4b-it-qat` (6,1 GB) y después `e2b-it-qat` (4,3 GB).
- **32 GB (la máquina del handoff):** ahí sí se puede probar `26b-a4b-it-qat` (16 GB) y ver si el
  salto de calidad en clasificación justifica el doble de RAM.

**Licencia: verificada.** El model card de **`google/gemma-4-12b-it`** en Hugging Face declara
`license: apache-2.0` — no la licencia propia de Google + Prohibited Use Policy con la que iba
Gemma 3. La decisión #1 justifica Qwen por "licencia Apache 2.0" y ese argumento **sobrevive** al
cambio de modelo. (Verificar igual el model card del tag exacto que se termine usando: la familia
tiene variantes y el campo se lee por modelo, no por familia.)

**Thinking: no hace falta un tag `-instruct`.** El dolor que motivó pasar de `qwen3-vl:8b-thinking`
a `-instruct` —el modelo gastaba los 4096 de `num_predict` razonando y devolvía 0 chars de
respuesta, 27 minutos por documento— no se repite acá: en Gemma 4 el razonamiento es **opt-in**, se
activa metiendo un token `<|think|>` al principio del system prompt. Sin eso, el modelo responde
directo. Nuestro `OllamaClient` ya manda `think: false` y el prompt no lleva ese token, así que el
comportamiento por defecto es el que queremos.

**Qué nos aporta.** Es un cambio de **una variable de entorno**: `OLLAMA_MODEL`. El adapter, el
prompt y el flujo quedan igual. Es el único de los tres que ataca directo el problema de RAM sin
tocar arquitectura ni romper la decisión #1 (inferencia en infraestructura propia, sin API externa).

**Lo que hay que hacer para cerrarlo (no es "leer y opinar"):** correr los casos de prueba que ya
tenemos armados (`docs/postman/test-docs`, casos BBVA celulares y tecnología portátil, incluido el
fraudulento con constancia de IMEI falsa) contra Qwen3-VL y contra Gemma 4, y comparar **tres
cosas**: clasificación acertada, calidad de la transcripción OCR de los PDF, y tiempo hasta la
clasificación (objetivo de la decisión #4: <10 min). Sin esa tabla, es una opinión.

---

## 3 · Kimi K3 — techo de calidad, no opción de despliegue

**Qué es.** El modelo de Moonshot AI, pesos liberados en julio de 2026: **2,8 billones de
parámetros** (MoE, 16 expertos activos de 896), contexto de **1M de tokens**, multimodal nativo
(texto + imagen).

**Por qué no lo podemos correr.** Los pesos solos son ~1,4 TB antes del overhead de runtime, y
Moonshot recomienda "supernodos de 64 o más aceleradores". No hay conversación posible con una
notebook, con Railway ni con el presupuesto de un proyecto final.

**Vía API, entonces.** Precio de lista de Moonshot: **US$3 / millón de tokens de entrada**
(US$0,30 con cache hit) y **US$15 / millón de salida**. El hermano menor, **Kimi K2.6** (abril
2026, multimodal, 262K de contexto), sale **US$0,54 / millón de entrada** y **US$2,28 / salida** en
OpenRouter — un orden de magnitud más barato.

**El problema no es el precio, es la decisión #1.** "LLM en infraestructura propia con Ollama. No
usamos Anthropic, OpenAI ni ninguna API externa. Razón: privacidad de datos, sin costo por token."
Mandar denuncias de siniestros con datos filiatorios, domicilio de riesgo y fotos del asegurado a
una API de un tercero (además alojada fuera del país) contradice el argumento con el que
justificamos el diseño ante la Disposición SSN 2/2023.

**Dónde sí nos sirve:** como **baseline de calidad medido**, con datos **sintéticos** (los fixtures
que ya generamos, no casos reales). Correr los mismos casos contra Kimi K2.6 vía OpenRouter y
publicar la comparación nos da algo que hoy no tenemos: una cifra de cuánto perdemos por elegir un
modelo chico y local. Eso es material directo para el documento de arquitectura y para el **paper
de CONAIISI** — "elegimos un modelo de 8–12B en infraestructura propia y la caída de precisión
frente a un frontier model fue de X puntos" es un argumento defendible; "elegimos el chico porque
era lo que entraba" no lo es.

---

## 4 · El problema que ninguna de las tres resuelve sola: dónde corre Ollama

Vale la pena dejarlo escrito porque es lo que va a decidir la card:

**Railway no ofrece GPU.** Su precio es por consumo: **US$10 / GB de RAM / mes** y **US$20 / vCPU /
mes**, con **US$5 de crédito incluido** en el plan Hobby. Ollama con un modelo cargado mantiene los
pesos residentes: 7–8 GB con un 8B en Q4 más el KV cache, que se reserva según `num_ctx` (nosotros
fijamos 32K) y no según lo que se use. Son **~US$70–100 por mes solo de RAM**, más CPU, más los
2 GB del sidecar de CLIP. Los US$5 de crédito se consumen en horas.

Opciones reales, de menor a mayor cambio:

| Opción | Qué implica | Contra |
|---|---|---|
| **A. Modelo más chico local** (`gemma4:12b-it-qat`, `e4b-it-qat`, o `qwen3-vl:4b`) | Solo cambia `OLLAMA_MODEL`. Entra en 16 GB. | No resuelve el despliegue: sigue sin poder correr en Railway. |
| **B. Ollama Cloud** | Es la **misma API**: `OLLAMA_BASE_URL=https://ollama.com` + `OLLAMA_API_KEY`. El `OllamaAdapter` no se toca. Free (1 modelo concurrente) o Pro US$20/mes. Tiene `gemma4`, `qwen3.5` y `kimi-k3` como modelos cloud. | Los datos salen de nuestra infraestructura → mismo choque con la decisión #1 que Kimi, aunque más acotado. Límites de uso por sesión (5 h) y semanales. |
| **C. VPS con RAM** (tipo Hetzner, ~€15/mes por 16 GB) corriendo Ollama, con la app en Railway | Mantiene la inferencia "en infraestructura propia" — el argumento de la decisión #1 sobrevive. | Otro servidor que administrar y otro gasto. |
| **D. Ollama en la notebook de alguien + túnel** | Costo cero. | Frágil para una demo con los profesores. Sirve para desarrollo, no para la entrega. |

**Recomendación:** A + C. Bajar el modelo para que el desarrollo sea usable en 16 GB (A), y llevar
la inferencia a un VPS chico para la instancia demo (C). B queda como plan de contingencia si no
hay presupuesto, **explicitando en el documento de arquitectura** que la decisión #1 se relaja para
el entorno de demostración y no para el diseño del producto.

A favor nuestro: todo esto ya es configurable por env var (`OLLAMA_BASE_URL`, `OLLAMA_MODEL`) y el
`ClaimClassifier` tiene su `MockClassifier`. Ninguna de estas opciones es una migración, son
variables de entorno. La decisión es reversible y se puede justificar con medición.

---

## 5 · Nota sobre la migración a Supabase

Como esto sale en la misma conversación, dejo verificado lo que aplica al plan gratuito (2026):

- **500 MB de base por proyecto**, 1 GB de storage, 5 GB de egress, hasta 2 proyectos activos.
- **El proyecto se pausa tras 1 semana de inactividad.** Esto es lo importante para nosotros:
  antes de una demo o una entrega, hay que despertar el proyecto con tiempo.
- **pgvector** está soportado (Supabase lo ofrece como su producto "Vector"), que es lo que
  necesita la decisión #11 para detectar imágenes reutilizadas entre denuncias. Verificar la
  versión de la extensión contra la que usa `db/init.sql`.
- Es Postgres estándar, así que los múltiples esquemas del multi-tenant (`arbiter_common`,
  `aseguradora_*`) funcionan igual. Con `ddl-auto=validate` el contenedor no arranca si falta algo:
  el seed hay que correrlo completo antes de levantar los servicios (mismo procedimiento que
  `scripts/db-railway.ps1 all`).
- 500 MB alcanza de sobra para el esquema y los casos de prueba, pero **los adjuntos no van ahí** —
  van a storage. Otro punto a favor de cerrar el `S3Adapter` (ver Floci).

---

## Próximos pasos propuestos

1. Bajar el modelo a `gemma4:12b` en la máquina de 16 GB y recalcular los `mem_limit` del compose
   contra esa RAM (el handoff avisa explícitamente que los números de 14g no son universales).
2. Correr los casos de prueba ya armados contra Qwen3-VL vs Gemma 4 y armar la tabla de
   comparación (acierto, OCR, tiempo). Es lo que cierra esta card con evidencia.
3. Medir el mismo set contra Kimi K2.6 vía API con datos sintéticos, como techo de referencia para
   el documento de arquitectura y el paper.
4. Decidir dónde corre la inferencia en la instancia demo (VPS vs Ollama Cloud) y dejarlo escrito
   como decisión de arquitectura con su justificación.
5. Levantar Floci en el compose de dev y escribir el `S3Adapter` cuando se aborde la persistencia
   de adjuntos.

---

## Fuentes

- Floci — sitio oficial: https://floci.io/aws/ · docs: https://fredpena-floci.mintlify.app/introduction · Quarkus Insights #256: https://quarkus.io/blog/quarkus-insights-256-what-is-floci/
- Gemma — releases: https://ai.google.dev/gemma/docs/releases · model card 26B-A4B: https://huggingface.co/google/gemma-4-26B-A4B-it · tags en Ollama: https://ollama.com/library/gemma4 · benchmark CPU: https://www.kunalganglani.com/blog/gemma-4-cpu-inference-benchmark
- Kimi — K3 y self-hosting: https://northflank.com/blog/what-is-kimi-k3-self-hosting · K2.6 en OpenRouter: https://openrouter.ai/moonshotai/kimi-k2.6
- Railway — precios: https://docs.railway.com/reference/pricing · límites por plan: https://docs.railway.com/reference/pricing/plans
- Ollama Cloud — docs: https://docs.ollama.com/cloud · planes: https://ollama.com/pricing · modelos cloud: https://ollama.com/search?c=cloud
- Supabase — precios y límites del plan free: https://supabase.com/pricing
