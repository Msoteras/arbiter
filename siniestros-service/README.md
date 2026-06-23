# siniestros-service — Claims Analysis and Classification Module

Classifies insurance claims using a local LLM (Ollama + Qwen3-VL). The model receives structured data from multiple sources (business rules, policy, insured history) and returns a classification with factors and confidence level.

## Possible Classifications

| Classification | Meaning |
|---|---|
| `FAST_TRACK` | Simple case, complete documentation, clean history → can be processed expeditiously |
| `FALTA_DOCUMENTACION` | Valid but incomplete case → requires specific documents from the insured |
| `POTENCIAL_RIESGO` | Inconsistencies, suspicious pattern, multiple recent claims → requires investigation |
| `REQUIERE_ANALISIS_MANUAL` | Ambiguity, complex context, missing data → escalated to specialist analyst |

## Requirements

- **Java 21**
- **Maven 3.9+**
- **Docker** and **Docker Compose** (to run Ollama in a container)
- Port **8082** free (Spring Boot server)
- Port **11434** free (Ollama)

## Module Structure

```
siniestros-service/
├── src/main/java/.../siniestros/
│   ├── adapters/
│   │   ├── SiniestroClassifier.java      # classifier interface
│   │   ├── DocumentAnalyzer.java         # attachment vision-analysis interface
│   │   └── OllamaAdapter.java            # implements both, against Ollama
│   ├── config/
│   │   ├── OllamaConfig.java             # RestClient bean for Ollama
│   │   └── OllamaProperties.java         # properties (URL, model, prompt version)
│   ├── dto/
│   │   ├── ClasificacionRequest.java     # classifier input
│   │   └── ClasificacionResponse.java    # output (classification + factors + confidence)
│   └── exceptions/
│       └── ClasificacionInvalidaException.java
├── src/main/resources/
│   ├── application.yml
│   └── prompts/
│       └── clasificacion-v1.md           # prompt template (versioned)
└── src/test/
    ├── java/.../adapters/
    │   ├── OllamaAdapterIntegrationTest.java       # basic test against Ollama
    │   ├── OllamaClasificacionEscenariosTest.java  # parameterized tests with fixtures
    │   └── MockSiniestroClassifier.java             # mock for unit tests
    └── resources/fixtures/
        ├── escenario-posible-riesgo.json     # recidivist, inconsistencies
        ├── escenario-sin-riesgo.json         # first claim, solid report
        └── escenario-fast-track.json         # screen break, simple and verifiable
```

## How to Run

### 🚀 Option 1: Everything in Docker (recommended)

Starts Ollama + siniestros-service in containers:

```bash
# From project root
docker-compose up -d --build
```

**First time:** ~2-3 min (compiles Java + downloads Qwen3-VL model ~10GB)
**Subsequent runs:** ~30s (uses cache)

Check logs:
```bash
docker-compose logs -f siniestros-service
```

When you see `Started SiniestrosServiceApplication` → ready at `http://localhost:8082`

Stop:
```bash
docker-compose down
```

Clean everything (including model):
```bash
docker-compose down -v
```

---

### 🧪 Option 2: Parameterized tests with Docker

Compiles, starts Ollama, runs 11 parameterized scenarios in series (not parallel):

```bash
docker compose -f docker-compose.test.yml up --exit-code-from siniestros-test
```

Wait ~3-5 min (each scenario takes 10-30s in Ollama).

---

### 💻 Option 3: Local dev with Ollama

If you have Ollama installed locally:

```bash
# Terminal 1: Ollama
ollama serve

# Terminal 2: Download model (first time)
ollama pull qwen3-vl

# Terminal 3: Spring Boot service
mvn spring-boot:run -pl siniestros-service
```

Service starts at `http://localhost:8082`

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server URL |
| `OLLAMA_MODEL` | `qwen3-vl` | Model to use for classification |

## Test Fixtures

Scenarios are in `src/test/resources/fixtures/`. Each JSON has:

```json
{
  "name": "Scenario description",
  "expectedClassification": "POTENCIAL_RIESGO | FAST_TRACK | FALTA_DOCUMENTACION | REQUIERE_ANALISIS_MANUAL",
  "request": {
    "branch": "...",
    "product": "...",
    "claimCause": "...",
    "insuredItem": "...",
    "description": "...",
    "attachmentsOcr": ["..."],
    "insurerRules": "...",
    "insuredHistory": "..."
  }
}
```

To add a new scenario: create a JSON with the same structure in `fixtures/` and add it to the `@ValueSource` in `OllamaClasificacionEscenariosTest`.

## Testing with Postman

### 📥 Import collection

1. Open **Postman**
2. **Collections** → **Import**
3. Select: `Arbiter_Siniestros_Clasificacion.postman_collection.json`
4. ✅ You have 5 requests + 1 results endpoint ready

### 🔄 Full flow

**ASYNC flow** (recommended to see results in file):

```
1. POST http://localhost:8082/api/v1/claims
   multipart/form-data:
     - part "claim" (application/json): branch, product, claimCause, description, etc.
     - part "files" (0..N, optional): invoice, repair quote, police report, photo of the item
   → each file is sent to Ollama (vision model) to extract its relevant content, then
     background classification is triggered immediately.
   → 202 Accepted, returns claimId
   Writes result to file `./resultados-clasificaciones.md`

2. (optional, any time later) POST http://localhost:8082/api/v1/claims/{claimId}/attachments
   multipart/form-data, field "files": one or more additional documents/photos — e.g. the
   insured provides what was missing after a FALTA_DOCUMENTACION result.
   → each file is analyzed and added to what was already extracted for this claim, and the
     claim is reclassified automatically with the updated context.
   → 202 Accepted. Can be called as many times as needed.
```

**SYNC endpoint** (if you want immediate response, skips the attachment-analysis step — you must
provide already-extracted `attachmentsOcr` text yourself):

```
POST http://localhost:8082/api/v1/classifications
```

- Returns **200 OK** with classification (but blocks up to 30s)

**View accumulated results:**

```
GET http://localhost:8082/api/v1/claims/results
```

- Returns markdown table with all processed claims (one row per classification — a claim that
  was reclassified after new attachments shows up more than once, with its own timestamp)

### 📊 Suggested workflow

1. Run `POST /api/v1/claims` (with or without initial attachments) for each scenario
2. Wait ~15-30s, run `GET /results` to see the first classification
3. For a `FALTA_DOCUMENTACION` case, `POST /{claimId}/attachments` with the missing document and
   check `/results` again for the updated row
4. Verify expected vs obtained classifications

## Classification Flow Architecture

```
Claim (frontend/Postman)
    │
    ▼
ClasificacionController ──► ClasificacionOrquestador
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
            AseguradoraAdapter  ReglasAdapter   SiniestroClassifier
            (policy+history)    (branch rules)  (Ollama/LLM)
                    │               │               │
                    ▼               ▼               ▼
              Mock / REST      Mock / REST     OllamaAdapter
              (insurer DB)     (reglas-service) (qwen3-vl)
```

In dev/test the mocks are used (`MockAseguradoraAdapter`, `MockReglasAdapter`).
In production they will be replaced by real REST implementations.

## Key API Fields

| Field | Type | Description |
|---|---|---|
| `branch` | String | Insurance branch (e.g. "Celulares") |
| `product` | String | Commercial product variant |
| `claimCause` | String | Cause of loss (e.g. "Robo en vía pública") |
| `insuredItem` | String | Insured item with IMEI |
| `insuredId` | String | Insured's ID (DNI) |
| `policyNumber` | String | Policy number |
| `description` | String | Free-text description from the insured |
| `eventDate` | LocalDateTime | Date and time of the incident |
| `eventLocation` | String | Location of the incident |
| `attachmentsOcr` | List\<String\> | Text extracted by Ollama from each attachment. Populated automatically via `POST /{claimId}/attachments` — not sent by the client at creation time (only used directly by the sync `/classifications` endpoint) |
