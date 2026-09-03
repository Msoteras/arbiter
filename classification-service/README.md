# classification-service — Claims Analysis and Classification Module

Classifies insurance claims using a local LLM (Ollama + Qwen3-VL, with Gemini/Vertex as an
alternate provider). The model receives structured data from multiple sources (business rules,
policy, insured history, document OCR) and returns a classification with factors and confidence
level.

This module **does not own the case** — `cases-service` does. `classification-service` only runs
the analysis (Fast Track gate + LLM fallback) and keeps the immutable audit log, correlated to the
caller's `caseId`. See the root [`Readme.md`](../Readme.md) and [`CLAUDE.md`](../CLAUDE.md) for how
this fits into the platform.

## Possible Classifications

| Classification | Meaning |
|---|---|
| `FAST_TRACK` | Deterministic gate (not the LLM) → simple case, goes straight to the analyst for approval |
| `FALTA_DOCUMENTACION` | Valid but incomplete case → requires specific documents from the insured |
| `LLM_RECOMIENDA_APROBAR` | No alerts found → LLM recommends approval (non-binding) |
| `LLM_NO_RECOMIENDA_APROBAR` | Inconsistencies, suspicious pattern, multiple recent claims → LLM recommends against approval (non-binding) |
| `LLM_SOLICITA_REVISION_MANUAL` | Ambiguity, complex context, missing data → LLM can't recommend either way, escalated to analyst for 100% manual review |

## Requirements

- **Java 21**, **Maven 3.9+**
- **Docker** and **Docker Compose** — for Postgres (+ pgvector), Ollama and the `clip-embedding`
  sidecar (see root [`Readme.md`](../Readme.md) for the full dev-environment commands, including
  the Ollama-vs-Gemini switch scripts)
- Port **8082** free (this service)
- Port **11434** free (Ollama) — not needed if you run with `LLM_PROVIDER=gemini`

## Module Structure

```
classification-service/
├── src/main/java/.../classification/
│   ├── controllers/
│   │   ├── ClaimController.java            # internal API — called by cases-service (§ below)
│   │   ├── ClassificationController.java   # isolated-testing API — no case required
│   │   ├── ImageEmbeddingController.java   # duplicate-image check (CLIP + pgvector)
│   │   └── InsuredFraudRecordController.java
│   ├── adapters/
│   │   ├── ClaimClassifier / ClaimClassifierImpl   # orchestrates Fast Track + LLM fallback
│   │   ├── LlmClient (OllamaClient / GeminiClient)  # provider-agnostic LLM interface
│   │   ├── DocumentAnalyzer / DocumentAnalyzerImpl  # vision OCR on attachments
│   │   ├── ClipClient                                # embedding-service sidecar client
│   │   ├── GoogleVisionClient                        # optional web-match escalation
│   │   └── InsurerAdapter / RulesAdapter (+ Mock/REST impls)
│   ├── services/
│   │   ├── ClaimClassificationService / ClassificationOrchestrator
│   │   ├── FastTrackValidator, CoverageRuleEvaluator, CoverageScopeEvaluator,
│   │   │   TemporalRuleEvaluator, FraudRecordRuleEvaluator   # deterministic rule evaluators
│   │   ├── PromptBuilder                              # loads prompts/<prompt-version>.md
│   │   ├── ImageEmbeddingService, ImageFraudAnalysisService
│   │   └── ClassificationResultsService, InsuredFraudRecordService
│   ├── config/          # Ollama/Gemini/Embedding/GoogleVision properties, security, async, pgvector
│   ├── dto/              # ClassificationRequest/Response, BusinessRules, InsuredHistory, ...
│   ├── models/           # entities + repositories owned by this module
│   └── exceptions/
├── src/main/resources/
│   ├── application.yml
│   └── prompts/
│       ├── classification-v4.md          # current prompt (see `arbiter.llm.prompt-version`)
│       ├── classification-v3.md          # superseded — kept only so old ClassificationLog
│       │                                  # entries logged with this version stay auditable
│       └── extraccion-documento-v3.md    # document-OCR prompt (DocumentAnalyzerImpl)
└── src/test/
    ├── java/.../adapters/
    │   ├── OllamaAdapterIntegrationTest.java
    │   ├── OllamaClassificationScenariosTest.java   # parameterized tests with fixtures
    │   └── MockClaimClassifier.java
    └── resources/fixtures/          # escenario-*.json used by the parameterized tests
```

`ClaimReport` (the shared input DTO) lives in `common-lib` — see it for the exact fields
(`branch`, `product`, `claimCause`, `coverageId`, `claimCauseId`, `insuredItem`, `insuredId`,
`policyNumber`, `description`, `eventDate`, `eventLocation`, `claimedAmount`, `reportedAt`,
`policeReportAt`, `imageConsent`, `attachmentsOcr`).

## How to Run

For the full dev workflow (Docker Compose, Ollama vs. Gemini, everything-together, against
Railway's DB) see the root [`Readme.md`](../Readme.md#desarrollo-local) — it's shared across all
backend modules and kept there to avoid two copies drifting apart.

Quick local run against mocks (no Ollama, no Postgres needed for `dev` profile adapters):

```bash
# From project root
mvn spring-boot:run -pl classification-service
```

Service starts at `http://localhost:8082`. Swagger UI: `http://localhost:8082/swagger-ui.html`.

Parameterized scenario tests against a real Ollama container:

```bash
docker compose -f docker-compose.test.yml up --exit-code-from siniestros-test
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `LLM_PROVIDER` | `ollama` | `ollama` or `gemini` — who serves the classification model |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server URL |
| `OLLAMA_MODEL` | `qwen3-vl:8b-instruct` | Model to use (must be the `-instruct` tag, see `CLAUDE.md`) |
| `OLLAMA_NUM_CTX` | `32768` | Context window |
| `GOOGLE_CLOUD_PROJECT` / `GOOGLE_CLOUD_LOCATION` / `GEMINI_MODEL` | — / `global` / `gemini-3.5-flash` | Only used when `LLM_PROVIDER=gemini`; auth via ADC, no API key |
| `EMBEDDING_SERVICE_URL` | `http://localhost:8000` | CLIP sidecar (`embedding-service`) for duplicate-image detection |
| `EMBEDDING_SIMILARITY_THRESHOLD` | `0.90` | Minimum cosine similarity to flag a duplicate |
| `GOOGLE_VISION_ENABLED` / `GOOGLE_VISION_API_KEY` | `false` / — | Opt-in web-image-match escalation, requires insured consent (`imageConsent`) |
| `RULES_SERVICE_URL` | `http://localhost:8081` | `rules-service` base URL |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | local Postgres | Shared with the rest of the platform |
| `JWT_SECRET` | — | Same secret across all modules (see `auth-service`) |

## Testing with Postman

Two collections in `docs/postman/`:

- **`Arbiter_Cases_EndToEnd.postman_collection.json`** — the real flow: creates a case in
  `cases-service`, which calls this module's internal `POST /api/v1/claims` for you. This is how
  production traffic actually reaches classification.
- **`Arbiter_Claims_Classification.postman_collection.json`** — hits this module directly, for
  testing the analysis in isolation.

### Isolated testing (no case, no cases-service)

```
POST http://localhost:8082/api/v1/classifications
  multipart/form-data:
    - part "claim" (application/json): branch, product, claimCause, description, etc.
    - part "documents" (0..N, optional, one part per document type, e.g. "police_report", "item_photo")
  → runs the deterministic Fast Track gate first; if it doesn't qualify, extracts the attached
    documents with the vision model and falls back to full LLM classification.
  → 202 Accepted, async — poll GET /results below.
```

Requires a JWT with role `ANALISTA_SINIESTROS` or `REFERENTE_ASEGURADORA`.

```
GET http://localhost:8082/api/v1/classifications/results
```

- Returns a markdown table (`Content-Type: text/markdown`) with every isolated run — one row per
  classification.

### Internal API (module-to-module — this is what `cases-service` calls)

```
POST   /api/v1/claims                    multipart, requires "caseId" — kicks off async analysis
GET    /api/v1/claims/{caseId}           poll for the result (null fields until it finishes)
POST   /api/v1/claims/{caseId}/decision  persist the analyst's verdict (service-token only)
```

### Duplicate-image check

```
POST /api/v1/image-embeddings/check-duplicate
  multipart/form-data: "caseId", optional "attachmentLabel", part "image"
  → embeds the image via the CLIP sidecar and searches pgvector for matches above the threshold.
```

## Classification Flow Architecture

```
cases-service (owns the case)
    │
    ▼  POST /api/v1/claims  (internal API)
ClaimController ──► ClaimClassificationService ──► ClassificationOrchestrator
                                                          │
                    ┌──────────────┬───────────────┬─────┴─────┬───────────────┐
                    ▼              ▼               ▼           ▼               ▼
            FastTrackValidator  InsurerAdapter  RulesAdapter  DocumentAnalyzer  ImageEmbeddingService
            + rule evaluators   (policy+history) (branch rules) (vision OCR)    (CLIP + pgvector)
                    │              │               │           │               │
                    ▼              ▼               ▼           ▼               ▼
              (deterministic)  Mock / REST     Mock / REST   LlmClient       ClipClient
                                (insurer DB)    (rules-service) (Ollama/Gemini) (embedding-service)
```

In dev/test the mocks are used (`MockInsurerAdapter`, `MockRulesAdapter`). In production they're
replaced by the REST implementations against the insurer's DB and `rules-service` respectively.
