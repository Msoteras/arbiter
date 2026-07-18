# Integración de detección de imágenes en el motor de scoring de riesgo

**Estado:** propuesta · pendiente de mergear `develop` (motor de scoring) en la rama del PoC de embeddings.

Este documento describe cómo conectar la detección de imágenes duplicadas (PoC actual con CLIP + pgvector) al **motor de scoring de riesgo** que vive en `develop`, para que la reutilización de imágenes **pondere en el score de fraude** que ve el analista, en vez de ser un warning cosmético.

---

## Contexto

### Qué hay hoy en la rama del PoC (`feature/poc-ollama-embeddings`)

- **Sidecar CLIP** (`embedding-service/`, Python + FastAPI + open_clip ViT-B-32): genera embeddings **determinísticos** de 512 dims. La misma imagen produce siempre el mismo vector.
- **`ImageEmbeddingService`**: genera el embedding, lo persiste en `image_embedding` (columna `vector(512)` de pgvector) y busca similares por distancia coseno.
- **Endpoint de prueba** `POST /api/v1/image-embeddings/check-duplicate` (se mantiene, útil para QA manual).
- **Integración actual (a reemplazar):** `ClaimClassificationService.checkDuplicateImages()` corre para *todos* los claims, después del LLM, y solo agrega factores de warning (`⚠ Imagen similar…`) al response. **No alimenta ninguna decisión ni score.**

### Qué hay en `develop` (motor de scoring)

- **`RiskScoringService`**: calcula un score de fraude en `[0.0, 1.0]` como suma ponderada de los factores activos según la config de la aseguradora (`BusinessRules.ScoringConfig`), normalizada por el peso total. Devuelve un `RiskScore` (score + `RiskBand` + breakdown por factor).
- **`RiskFactorEvaluator`** (interfaz): cada factor es una **función pura** de `RiskContext` que devuelve una `Contribution(factorId, score∈[0,1], rationale)`. Los evaluators son beans Spring, autodescubiertos por `RiskScoringService` vía `List<RiskFactorEvaluator>`.
- **`RiskContext`**: `{ claim, policy, history, rules }` — armado con los **mismos datos** que el orchestrator ya trae para clasificar. Ningún evaluator hace llamadas externas.
- **`RiskFactorIds`**: ids estables. Ya hay ids reservados sin evaluator todavía (`PURCHASE_TO_REPORT_TIME`, `DOCUMENT_INCONSISTENCY`) → el patrón "agregar un factor nuevo" ya está previsto.
- **Wiring:** `ClassificationOrchestrator.withRiskScore(...)` corre en todas las rutas (falta doc, fast track, LLM), arma el `RiskContext` y adjunta el `RiskScore` al `ClassificationResponse` (`.toBuilder().riskScore(...)`). Si el scoring falla, la clasificación sigue sin score — es señal de apoyo, nunca gatea nada.

---

## Principio de diseño a respetar

Los evaluators son **funciones puras** de `RiskContext`, sin I/O. La llamada a CLIP (que es I/O) pasa **una sola vez en el orchestrator**; el resultado viaja como señal dentro del `RiskContext`, y el evaluator solo lo gradúa. Idéntico a cómo `AmountRatioEvaluator` lee `claim/policy` del context sin ir a buscar nada.

---

## Factor 1 — `image_reuse` (reutilización interna, entre siniestros)

Detecta que la imagen de este siniestro es igual/similar a un adjunto de **otro siniestro ya cargado** en el sistema. Es el PoC actual, ahora enchufado al score.

### Cambios, en orden

**1. Señal de duplicación — nuevo record**
`classification/services/risk/ImageDuplicationSignal.java`
```java
public record ImageDuplicationSignal(
        int imagesChecked, int duplicatesFound,
        double maxSimilarity, List<DuplicateImageMatch> matches) {
    public static ImageDuplicationSignal none() {
        return new ImageDuplicationSignal(0, 0, 0.0, List.of());
    }
}
```

**2. Extender `RiskContext`** con un campo opcional (nullable) al final:
```java
public record RiskContext(ClaimReport claim, InsuredPolicy policy,
        InsuredHistory history, BusinessRules rules,
        ImageDuplicationSignal imageDuplication) {}
```
Los evaluators existentes (`AmountRatioEvaluator`, etc.) no lo tocan; siguen leyendo lo suyo.

**3. Nuevo id + evaluator**
- `RiskFactorIds.IMAGE_REUSE = "image_reuse"` (junto a los otros).
- `risk/evaluators/ImageReuseEvaluator.java`:
```java
@Component
public class ImageReuseEvaluator implements RiskFactorEvaluator {
    public String factorId() { return RiskFactorIds.IMAGE_REUSE; }
    public Contribution evaluate(RiskContext ctx) {
        var sig = ctx.imageDuplication();
        if (sig == null || sig.imagesChecked() == 0)
            return new Contribution(factorId(), 0.0, "Sin imágenes para comparar — factor no evaluable");
        if (sig.duplicatesFound() == 0)
            return new Contribution(factorId(), 0.0, "Ninguna imagen coincide con siniestros previos");
        return new Contribution(factorId(), Math.min(1.0, sig.maxSimilarity()),
            String.format("Imagen %.0f%% similar a adjunto de siniestro previo — posible reutilización",
                sig.maxSimilarity() * 100));
    }
}
```

**4. Orchestrator: correr CLIP solo en el camino no-fast-track**
- Agregar dependencia `ImageEmbeddingService` y parámetro `caseId` a `classify(...)`.
- El gate de fast-track ya está: si el siniestro califica, `withRiskScore` recibe `imageDuplication = null` → el evaluator devuelve 0. **Los fast-track no pagan el costo de CLIP.**
- En el camino no-fast-track, antes de armar el `RiskContext`, iterar los adjuntos imagen, llamar `imageEmbeddingService.processAndFindDuplicates(caseId, label, filename, base64)`, y construir la `ImageDuplicationSignal` (reemplaza al `checkDuplicateImages` actual).
- `withRiskScore(...)` pasa la señal a `new RiskContext(claim, policy, history, rules, signal)`.

**5. Thread del `caseId`**
`ClaimClassificationService.processClaimClassification(caseId, ...)` → `orchestrator.classify(caseId, claim, documents)`. Los otros dos entrypoints (`processIsolatedClassification` sin caseId, `classify(claim)` sin docs) pasan `caseId=null` / señal vacía → sin detección.

**6. Activar el factor en la config semilla** de la aseguradora (`BusinessRules.ScoringConfig`): agregar `{ factorId: "image_reuse", weight: N }`. El peso se define según cuánto debe pesar el fraude por imagen vs. monto/frecuencia.

**7. Limpieza**: borrar de `ClaimClassificationService` el `checkDuplicateImages` + `enrichWithDuplicateFactors` (obsoletos — la señal ahora entra por el score).

### Qué se gana
- La reutilización de imágenes **pondera en el score real** que ve el analista, con su rationale.
- Queda en el **breakdown persistido** (`RiskBreakdownJsonConverter` de develop) → cumple auditoría (Disp. 2/2023) sin trabajo extra.
- El endpoint `/check-duplicate` **queda** como está, útil para pruebas manuales.

---

## Factor 2 — `image_web_match` (coincidencia con imágenes de internet) · A DEFINIR

**Idea:** además de comparar contra siniestros previos internos, chequear si la imagen del siniestro **aparece en internet** (stock photos, Pinterest, publicaciones de venta, resultados de Google Images). Si el "daño" que denuncia el asegurado es en realidad una foto sacada de la web, es una señal de fraude fortísima.

Encaja **conceptualmente igual** que `image_reuse`: sería otro `RiskFactorEvaluator` (`RiskFactorIds.IMAGE_WEB_MATCH`) que lee una señal precomputada en el `RiskContext` (misma mecánica: la llamada externa pasa en el orchestrator, el evaluator solo gradúa).

### ¿Se puede hacer sin mandar la imagen del asegurado afuera?

Para comparar contra "internet" hace falta **algo contra qué comparar** — un índice de referencia. Ese índice puede vivir en dos lugares, y ahí está toda la diferencia:

- **(a) El índice lo tiene un tercero** (Google, TinEye, Bing): les mandás la imagen del asegurado (o una URL pública a ella) y ellos la buscan. **No hay forma de evitar el envío**: estos servicios de búsqueda inversa **no aceptan un hash ni un embedding** como consulta — necesitan la imagen real o una URL. La idea intuitiva de "mando solo una huella en vez de la foto" **no funciona** con ellos. Siempre sale un derivado del asegurado hacia afuera → rompe la Decisión #1 (privacidad de datos) e introduce costo + dependencia externa.

- **(b) El índice lo tenemos nosotros**: descargamos/indexamos el material de referencia, y la comparación pasa **entera dentro de nuestra infra**. La foto del asegurado se embebe localmente (ya lo hace el `embedding-service`) y se compara localmente contra nuestro corpus en pgvector. **Nada del asegurado sale del sistema.**

**Conclusión:** sí se puede sin mandar la imagen afuera, **pero solo con la opción (b)** — hosteando nosotros el corpus de referencia. La búsqueda inversa "real" contra toda la web (opción a) es inviable sin romper la Decisión #1, y no existe una tercera vía práctica (la búsqueda inversa homomórfica/cifrada es teórica, ningún servicio comercial la soporta).

### Enfoque recomendado: corpus curado self-hosted

Idéntico al pipeline de `image_reuse`, cambia solo contra qué tabla se compara:

1. Armar un dataset de imágenes "sospechosas de origen": fotos de stock, publicaciones de venta (MercadoLibre para el ramo celulares), catálogos de fabricantes.
2. Correr ese dataset por el **mismo `embedding-service`** → embeddings de 512 dims → guardarlos en una tabla pgvector aparte (`reference_image_embedding`).
3. Al entrar un siniestro, embeber su foto (local) y compararla contra ese corpus con la misma query de coseno. Match fuerte → señal de "esta foto es de una publicación/stock, no del bien real".

Reusa el sidecar, pgvector y el patrón evaluator. **Cero dependencia externa, cero fuga de datos** → no rompe la Decisión #1.

**Trade-off honesto:** cubre solo lo que indexamos, no "todo internet". Pero para un ramo acotado (celulares) indexar las fotos típicas de venta/stock de esos modelos es dirigido y efectivo — es justo donde se da el fraude de "subo la foto de la publicación como si fuera mi teléfono roto".

### Comparativa de opciones

| Opción | Cómo | ¿Sale la imagen del asegurado? | Trade-off |
|--------|------|-------------------------------|-----------|
| API de búsqueda inversa (Google Vision, Bing, TinEye, SerpAPI) | El orchestrator manda la imagen o una URL pública al servicio. | **Sí** — obligatorio, no aceptan hash/embedding. | Cubre toda la web, pero rompe Decisión #1 + costo por request. |
| **Corpus curado self-hosted (CLIP + pgvector)** ✅ | Precargar embeddings de stock/venta en `reference_image_embedding` y comparar local. | **No** — todo pasa dentro de la infra. | Cobertura limitada al dataset, pero **cero fuga de datos y cero dependencia externa**. Reusa lo ya construido. |

---

## Puntos a cerrar con el equipo

1. **Peso** de `image_reuse` (y eventualmente `image_web_match`) en la `ScoringConfig`.
2. **Umbral**: hoy `0.90` en `EmbeddingProperties`. Para scoring quizás convenga bajarlo y dejar que el peso module, en vez de un corte duro.
3. **`image_web_match`**: vía recomendada = corpus curado self-hosted (no rompe la Decisión #1). A definir: qué fuentes indexar (stock, MercadoLibre, catálogos) y cómo mantener el dataset actualizado. La API externa de búsqueda inversa solo si el equipo decide flexibilizar la Decisión #1.
4. **Conflictos de merge esperables** al traer develop: `ClassificationOrchestrator.java` (ambas ramas lo tocan) y `ClassificationResponse` (develop le agrega `riskScore` al builder).
