# Detección de fraude por imágenes — análisis en cascada e integración al scoring

**Estado:** análisis en cascada **implementado** en `feature/poc-ollama-embeddings` · integración al motor de scoring **pendiente** de mergear `develop`.

Este documento cubre dos cosas:
1. **La cascada de análisis de imágenes** (ya implementada): cómo se detecta que una imagen del siniestro fue reutilizada, comparando primero contra nuestra base y escalando a internet solo si hace falta.
2. **La integración al motor de scoring** (pendiente): cómo esas señales pasan de ser texto informativo a **ponderar el score de fraude** que ve el analista.

---

## Parte 1 — El análisis en cascada (implementado)

### El principio: escalar solo cuando hace falta

El análisis corre **únicamente en la revisión profunda** (no Fast Track) y, por cada imagen, sigue este orden:

```
Clasificación (orchestrator)
  └─> ¿deterministicFastTrack?
        ├─ SÍ  → se saltea el análisis (el gate determinístico ya resolvió)
        └─ NO  → ImageFraudAnalysisService.analyze()
                  │
                  └─ por cada imagen:
                       1. CLIP + pgvector contra NUESTRA base   (gratis · privado · rápido)
                          ├─ matcheó → se corta acá
                          └─ no matcheó ↓
                       2. Google Vision Web Detection           (pago · sale de la infra)
```

**Por qué este orden.** El paso 1 no cuesta nada y no expone datos. El paso 2 cuesta plata y saca la imagen del asegurado fuera de nuestra infraestructura, así que es el **fallback**, nunca la primera jugada. Y si el paso 1 ya encontró que la imagen se reutilizó de otro siniestro, el hallazgo está establecido: escalar no aportaría nada.

**El corte es por imagen, no global.** Si el siniestro tiene tres fotos y una matchea internamente, solo esa se corta; las otras dos igual se escalan.

### Piezas

| Clase | Rol |
|---|---|
| `ImageFraudAnalysisService` | Orquesta la cascada. Es el único lugar que decide cuándo se escala. |
| `ImageFraudAnalysis` (DTO) | Resultado: hallazgos + `traces` + `webChecksPerformed`. |
| `ImageEmbeddingService` + `ClipClient` | Paso 1 — embeddings de 512 dims (CLIP ViT-B-32) y búsqueda por coseno en pgvector. |
| `GoogleVisionClient` + `GoogleVisionProperties` | Paso 2 — Web Detection. `enabled: false` por default. |
| `ClaimClassificationService` | Aplica el gate de Fast Track y vuelca las trazas a los factores. |

### Trazabilidad

`ImageFraudAnalysis.traces()` registra **cada paso, haya hallazgo o no**:

- `Imagen 'damage_photo': sin coincidencias con adjuntos de siniestros previos`
- `⚠ Imagen 'damage_photo': 97% similar a un adjunto del siniestro #42 ('stolen.jpg')`
- `⚠ Imagen 'damage_photo': publicada en internet — 2 coincidencia(s) exacta(s)…`

Registrar también los chequeos limpios es deliberado: la auditoría tiene que poder mostrar que el control **corrió**, no solo cuándo saltó. Además `webChecksPerformed` deja asentado cuántas imágenes salieron efectivamente de la infra — dato auditable de exposición, no un simple contador.

### Degradación segura

Ninguna falla del análisis rompe la clasificación. Si pgvector se cae, igual se escala a la web; si Vision falla, no se cuenta como chequeo realizado y el flujo sigue. Todo queda en la traza.

### Endpoints

- `POST /api/v1/image-embeddings/check-duplicate` — se mantiene para QA manual del paso 1.
- **No hay endpoint para el paso 2**: la búsqueda web se invoca desde el flujo de análisis, no se expone. (Para probar Vision de forma aislada está la colección `postman/Google-Vision-WebDetection-PoC`.)

---

## Parte 2 — Integración al motor de scoring (pendiente)

Hoy las trazas se agregan como **texto** a los factores de la clasificación: sirven para que el analista lea, pero **no ponderan nada**. Al mergear `develop` hay que convertirlas en factores del score.

### Qué hay en `develop` (motor de scoring)

- **`RiskScoringService`**: calcula un score de fraude en `[0.0, 1.0]` como suma ponderada de los factores activos según la config de la aseguradora (`BusinessRules.ScoringConfig`), normalizada por el peso total. Devuelve un `RiskScore` (score + `RiskBand` + breakdown por factor).
- **`RiskFactorEvaluator`** (interfaz): cada factor es una **función pura** de `RiskContext` que devuelve una `Contribution(factorId, score∈[0,1], rationale)`. Los evaluators son beans Spring, autodescubiertos por `RiskScoringService` vía `List<RiskFactorEvaluator>`.
- **`RiskContext`**: `{ claim, policy, history, rules }` — armado con los **mismos datos** que el orchestrator ya trae para clasificar. Ningún evaluator hace llamadas externas.
- **`RiskFactorIds`**: ids estables. Ya hay ids reservados sin evaluator todavía (`PURCHASE_TO_REPORT_TIME`, `DOCUMENT_INCONSISTENCY`) → el patrón "agregar un factor nuevo" ya está previsto.
- **Wiring:** `ClassificationOrchestrator.withRiskScore(...)` corre en todas las rutas (falta doc, fast track, LLM), arma el `RiskContext` y adjunta el `RiskScore` al `ClassificationResponse` (`.toBuilder().riskScore(...)`). Si el scoring falla, la clasificación sigue sin score — es señal de apoyo, nunca gatea nada.

### Qué hay en `develop` (motor de scoring)

- **`RiskScoringService`**: calcula un score de fraude en `[0.0, 1.0]` como suma ponderada de los factores activos según la config de la aseguradora (`BusinessRules.ScoringConfig`), normalizada por el peso total. Devuelve un `RiskScore` (score + `RiskBand` + breakdown por factor).
- **`RiskFactorEvaluator`** (interfaz): cada factor es una **función pura** de `RiskContext` que devuelve una `Contribution(factorId, score∈[0,1], rationale)`. Los evaluators son beans Spring, autodescubiertos por `RiskScoringService` vía `List<RiskFactorEvaluator>`.
- **`RiskContext`**: `{ claim, policy, history, rules }` — armado con los **mismos datos** que el orchestrator ya trae para clasificar. Ningún evaluator hace llamadas externas.
- **`RiskFactorIds`**: ids estables. Ya hay ids reservados sin evaluator todavía (`PURCHASE_TO_REPORT_TIME`, `DOCUMENT_INCONSISTENCY`) → el patrón "agregar un factor nuevo" ya está previsto.
- **Wiring:** `ClassificationOrchestrator.withRiskScore(...)` corre en todas las rutas (falta doc, fast track, LLM), arma el `RiskContext` y adjunta el `RiskScore` al `ClassificationResponse` (`.toBuilder().riskScore(...)`). Si el scoring falla, la clasificación sigue sin score — es señal de apoyo, nunca gatea nada.

---

## Principio de diseño a respetar

Los evaluators son **funciones puras** de `RiskContext`, sin I/O. La cascada (que sí hace I/O: CLIP y eventualmente Vision) **ya corre una sola vez** en `ImageFraudAnalysisService`; su resultado viaja como señal dentro del `RiskContext`, y los evaluators solo lo gradúan. Idéntico a cómo `AmountRatioEvaluator` lee `claim/policy` del context sin ir a buscar nada.

**Consecuencia práctica:** la cascada ya produce el `ImageFraudAnalysis`. La integración al scoring **no reimplementa nada** — solo transporta ese objeto hasta el `RiskContext` y le agrega dos evaluators que lo leen.

---

## Cambios comunes a ambos factores

**1. Extender `RiskContext`** con un campo opcional (nullable) al final:
```java
public record RiskContext(ClaimReport claim, InsuredPolicy policy,
        InsuredHistory history, BusinessRules rules,
        ImageFraudAnalysis imageFraud) {}
```
Los evaluators existentes (`AmountRatioEvaluator`, etc.) no lo tocan; siguen leyendo lo suyo.

**2. Mover la invocación de la cascada al orchestrator.** Hoy `ClaimClassificationService` la llama después de clasificar; con el merge conviene que corra dentro del orchestrator (que es quien arma el `RiskContext`), en la ruta no-Fast-Track. Requiere pasarle el `caseId` a `classify(...)`.

**3. Thread del `caseId`**: `processClaimClassification(caseId, ...)` → `orchestrator.classify(caseId, claim, documents)`. Los entrypoints sin caseId (`processIsolatedClassification`, `classify(claim)`) pasan `null` → sin análisis.

---

## Factor 1 — `image_reuse` (reutilización entre siniestros)

Lee los hallazgos del **paso 1** de la cascada: la imagen coincide con un adjunto de otro siniestro ya cargado.

- `RiskFactorIds.IMAGE_REUSE = "image_reuse"`
- `risk/evaluators/ImageReuseEvaluator.java`:
```java
@Component
public class ImageReuseEvaluator implements RiskFactorEvaluator {
    public String factorId() { return RiskFactorIds.IMAGE_REUSE; }
    public Contribution evaluate(RiskContext ctx) {
        var fraud = ctx.imageFraud();
        if (fraud == null || fraud.imagesAnalyzed() == 0)
            return new Contribution(factorId(), 0.0, "Sin imágenes para comparar — factor no evaluable");
        if (fraud.internalDuplicates().isEmpty())
            return new Contribution(factorId(), 0.0, "Ninguna imagen coincide con siniestros previos");
        double max = fraud.internalDuplicates().stream()
                .mapToDouble(DuplicateImageMatch::similarity).max().orElse(0.0);
        return new Contribution(factorId(), Math.min(1.0, max),
            String.format("Imagen %.0f%% similar a adjunto de siniestro previo — posible reutilización", max * 100));
    }
}
```

**Activar el factor** en la config semilla de la aseguradora (`BusinessRules.ScoringConfig`): agregar `{ factorId: "image_reuse", weight: N }`. El peso se define según cuánto debe pesar el fraude por imagen vs. monto/frecuencia.

---

## Factor 2 — `image_web_match` (la imagen ya está publicada en internet)

Lee los hallazgos del **paso 2** de la cascada: la imagen aparece en una publicación de venta, un catálogo o una red social.

- `RiskFactorIds.IMAGE_WEB_MATCH = "image_web_match"`
- `risk/evaluators/ImageWebMatchEvaluator.java` — graduación sugerida (ver "Calibración" abajo):
```java
@Component
public class ImageWebMatchEvaluator implements RiskFactorEvaluator {
    public String factorId() { return RiskFactorIds.IMAGE_WEB_MATCH; }
    public Contribution evaluate(RiskContext ctx) {
        var fraud = ctx.imageFraud();
        if (fraud == null || fraud.webChecksPerformed() == 0)
            return new Contribution(factorId(), 0.0, "No se verificó contra internet — factor no evaluable");
        if (fraud.webMatches().isEmpty())
            return new Contribution(factorId(), 0.0, "Ninguna imagen fue encontrada publicada en internet");
        // Ver "Calibración": una exacta pesa mucho más que varias parciales.
        ...
    }
}
```

**Activar el factor**: `{ factorId: "image_web_match", weight: N }`.

⚠️ **Este factor solo aporta si `arbiter.google-vision.enabled=true`**, lo que requiere consentimiento del asegurado (ver abajo). Con Vision deshabilitado, `webChecksPerformed = 0` y el evaluator devuelve 0 sin ensuciar el score.

### Qué se gana con ambos factores
- El fraude por imagen **pondera en el score real** que ve el analista, con su rationale.
- Queda en el **breakdown persistido** (`RiskBreakdownJsonConverter` de develop) → cumple auditoría (Disp. 2/2023) sin trabajo extra.

---

## Calibración — resultados del PoC con Google Vision

Pruebas reales contra la API (julio 2026), que son la base para graduar el factor:

| Imagen | Origen | `full` | `partial` | `pages` | Lectura |
|---|---|---|---|---|---|
| Zapatilla Nike (CDN de MercadoLibre) | Catálogo | **2** | 10 | 10 | 🔴 Señal fuerte |
| Foto de celular bajada de redes | Internet | 0 | **3** | **10** | 🟠 Señal media |
| Foto propia sacada con el celular | Privada | **0** | **0** | **0** | 🟢 Sin señal |

### Reglas que salieron de estas pruebas

1. **`visuallySimilarImages` NO se usa.** Es "otras fotos de cosas parecidas", no "esta misma imagen". La foto propia devolvió **10** resultados ahí y **cero** matches reales: usarlo dispararía en el 100% de las denuncias legítimas. Ya está documentado en el javadoc de `WebImageMatch` para que nadie lo "arregle" después.

2. **La señal es graduable, no binaria.** Catálogo (exactas) > redes (parciales) > propia (nada). Conviene ponderar `fullMatchingImages` bastante más que `partialMatchingImages`.

3. **Los matches parciales son ruidosos.** En la prueba de redes, el mismo `media_id` de Instagram apareció como match parcial de cuatro páginas distintas sobre modelos de teléfono **diferentes**. Conviene exigir un mínimo de páginas distintas antes de considerar la señal fuerte.

4. **Cantidad de páginas ≈ tipo de fraude.** Muchas páginas comerciales → foto de catálogo (el bien ni siquiera es del asegurado). Una sola página → podría ser la publicación del propio asegurado, que **no** es fraude. Esto resuelve el falso positivo más obvio: el conteo discrimina entre "sacó la foto de un catálogo" y "publicó su propio teléfono".

5. **Límite conocido.** Cubre lo que Google tiene indexado. Una imagen chica o poco popular puede pasar desapercibida (nos pasó con una imagen de 16 KB bajada de internet que no dio matches).

### Bonus no previsto: `bestGuessLabels`

Vision devuelve qué cree que es la imagen (ej. *"Nike Women's Run Swift 3"*). Eso habilita un **chequeo cruzado gratis** contra el `insuredItem` declarado: si el asegurado declaró "Samsung A56" y la foto es una zapatilla, hay inconsistencia detectable sin LLM. Podría ser un tercer factor (`DOCUMENT_INCONSISTENCY` ya está reservado como id en `RiskFactorIds`).

---

## Nota sobre el enfoque alternativo self-hosted

La implementación actual del paso 2 usa Google Vision (opción **a** del análisis de abajo). La opción **b** —corpus curado propio— sigue siendo válida y **compatible con la cascada**: sería simplemente un tercer escalón, o un reemplazo del paso 2 para deployments que no puedan usar API externa. El análisis que sigue explica por qué no hay una tercera vía.

### ¿Se puede hacer sin mandar la imagen del asegurado afuera?

Para comparar contra "internet" hace falta **algo contra qué comparar** — un índice de referencia. Ese índice puede vivir en dos lugares, y ahí está toda la diferencia:

- **(a) El índice lo tiene un tercero** (Google, TinEye, Bing): les mandás la imagen del asegurado (o una URL pública a ella) y ellos la buscan. **No hay forma de evitar el envío**: estos servicios de búsqueda inversa **no aceptan un hash ni un embedding** como consulta — necesitan la imagen real o una URL. La idea intuitiva de "mando solo una huella en vez de la foto" **no funciona** con ellos. Siempre sale un derivado del asegurado hacia afuera → rompe la Decisión #1 (privacidad de datos) e introduce costo + dependencia externa.

- **(b) El índice lo tenemos nosotros**: descargamos/indexamos el material de referencia, y la comparación pasa **entera dentro de nuestra infra**. La foto del asegurado se embebe localmente (ya lo hace el `embedding-service`) y se compara localmente contra nuestro corpus en pgvector. **Nada del asegurado sale del sistema.**

**Conclusión:** sí se puede sin mandar la imagen afuera, **pero solo con la opción (b)** — hosteando nosotros el corpus de referencia. La búsqueda inversa "real" contra toda la web (opción a) es inviable sin romper la Decisión #1, y no existe una tercera vía práctica (la búsqueda inversa homomórfica/cifrada es teórica, ningún servicio comercial la soporta).

### La alternativa self-hosted: corpus curado

Idéntico al pipeline de `image_reuse`, cambia solo contra qué tabla se compara:

1. Armar un dataset de imágenes "sospechosas de origen": fotos de stock, publicaciones de venta (MercadoLibre para el ramo celulares), catálogos de fabricantes.
2. Correr ese dataset por el **mismo `embedding-service`** → embeddings de 512 dims → guardarlos en una tabla pgvector aparte (`reference_image_embedding`).
3. Al entrar un siniestro, embeber su foto (local) y compararla contra ese corpus con la misma query de coseno. Match fuerte → señal de "esta foto es de una publicación/stock, no del bien real".

Reusa el sidecar, pgvector y el patrón evaluator. **Cero dependencia externa, cero fuga de datos** → no rompe la Decisión #1.

**Trade-off honesto:** cubre solo lo que indexamos, no "todo internet". Pero para un ramo acotado (celulares) indexar las fotos típicas de venta/stock de esos modelos es dirigido y efectivo — es justo donde se da el fraude de "subo la foto de la publicación como si fuera mi teléfono roto".

### Comparativa de opciones

| Opción | Cómo | ¿Sale la imagen del asegurado? | Trade-off |
|--------|------|-------------------------------|-----------|
| **API de búsqueda inversa** (Google Vision) — *implementada, opt-in* | Se manda la imagen al servicio. | **Sí** — obligatorio, no aceptan hash/embedding. | Cubre toda la web indexada, pero tensiona la Decisión #1 + costo por request + requiere consentimiento. |
| **Corpus curado self-hosted** (CLIP + pgvector) — *no implementada* | Precargar embeddings de stock/venta en `reference_image_embedding` y comparar local. | **No** — todo pasa dentro de la infra. | Cobertura limitada al dataset, pero **cero fuga de datos y cero dependencia externa**. Reusa lo ya construido. |

### Política de privacidad de Google Vision (verificada)

Según la [FAQ oficial de uso de datos](https://docs.cloud.google.com/vision/docs/data-usage) del producto **Cloud** (distinto del Google Images de consumidor):

- **No usa el contenido** salvo para prestar el servicio; **no entrena** sus modelos con él.
- En operaciones online (las que usamos), la imagen **se procesa en memoria y no se persiste a disco**.
- **No comparte** el contenido con terceros.
- Sí loguea **metadata** de la request (timestamp, tamaño) para operación y antiabuso.

O sea: el argumento *"Google se queda con las fotos"* **no es cierto** para el producto Cloud. Pero quedan dos cosas en pie:

1. Es una garantía **contractual**, no técnica. El corpus self-hosted da una garantía física (la imagen nunca sale).
2. Aunque no la retengan, la imagen **viaja y se procesa fuera de Argentina** → es transferencia internacional de datos personales bajo la Ley 25.326, y eso exige base legal.

### Consentimiento — requisito para activar el paso 2

La base legal aplicable es el **consentimiento del asegurado**, que la ley pide **libre, expreso e informado** (art. 5) y con mención explícita de la transferencia internacional (art. 12). En la práctica:

- **Checkbox dedicado y visible** en el alta de la denuncia, no una cláusula enterrada en unos T&C genéricos.
- Texto que informe **finalidad** (verificación antifraude), **quién** recibe (proveedor de servicios) y **dónde** (exterior).
- **Libre**: si negarse impidiera denunciar, el consentimiento deja de ser válido. El flujo debe funcionar igual sin consentimiento — simplemente no se hace el paso 2 y el factor queda no evaluable (la cascada ya lo contempla: `webChecksPerformed = 0`).
- **Registrado** con timestamp junto a la denuncia, y **revocable**.
- **Por aseguradora**: el responsable del dato es cada tenant, así que la cláusula es configurable por aseguradora.

> Esto es orientación general de cómo suele estructurarse, no asesoramiento legal. Para producción real lo revisa un abogado de datos personales.

---

## Puntos a cerrar con el equipo

1. **Pesos** de `image_reuse` e `image_web_match` en la `ScoringConfig`.
2. **Umbral interno**: hoy `0.90` en `EmbeddingProperties`. Para scoring quizás convenga bajarlo y dejar que el peso module, en vez de un corte duro.
3. **Graduación de `image_web_match`**: definir la fórmula concreta a partir de la calibración (cuánto pesa una exacta vs. parciales, y el mínimo de páginas para considerar señal fuerte).
4. **Consentimiento en el wizard**: sumar el checkbox al bloque de consentimientos que ya estaba pendiente. Sin esto, `google-vision.enabled` no debería activarse en producción.
5. **¿Sumar el corpus self-hosted** como escalón adicional o como reemplazo del paso 2 para deployments sin API externa?
6. **`bestGuessLabels` como tercer factor** (inconsistencia entre lo declarado y lo que muestra la foto) — el id `DOCUMENT_INCONSISTENCY` ya está reservado.
7. **Conflictos de merge esperables** al traer develop: `ClassificationOrchestrator.java` (ambas ramas lo tocan) y `ClassificationResponse` (develop le agrega `riskScore` al builder).
