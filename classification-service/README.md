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
│   │   ├── ClaimController.java               # internal API — called by cases-service (see below)
│   │   └── InsuredFraudRecordController.java  # fraud records per insured (/api/v1/fraud-records)
│   ├── adapters/
│   │   ├── ClaimClassifier / ClaimClassifierImpl    # LLM classification call
│   │   ├── LlmClient (OllamaClient / GeminiClient)  # provider-agnostic LLM interface
│   │   ├── DocumentAnalyzer / DocumentAnalyzerImpl  # vision OCR on attachments
│   │   ├── ClipClient                               # embedding-service sidecar client
│   │   ├── GoogleVisionClient                       # optional web-match escalation
│   │   ├── InsurerAdapter (db/InsurerDatabaseAdapter, mock/MockInsurerAdapter)
│   │   └── RulesAdapter (RulesRestAdapter, BaselineRulesAdapter)
│   ├── services/
│   │   ├── ClaimClassificationService / ClassificationOrchestrator
│   │   ├── FastTrackValidator, CoverageRuleEvaluator, CoverageScopeEvaluator,
│   │   │   TemporalRuleEvaluator, FraudRecordRuleEvaluator   # deterministic rule evaluators
│   │   ├── risk/                                  # risk score and its factor evaluators
│   │   ├── PromptBuilder                          # loads prompts/<prompt-version>.md
│   │   ├── ImageEmbeddingService, ImageFraudAnalysisService
│   │   └── ClassificationResultsService, InsuredFraudRecordService
│   ├── config/          # Ollama/Gemini/Embedding/GoogleVision properties, security, async, tenant
│   ├── dto/
│   ├── models/          # entities + repositories owned by this module
│   └── exceptions/
└── src/main/resources/
    ├── application.yml
    └── prompts/
        ├── classification-v5.md          # current prompt (`arbiter.llm.prompt-version`)
        ├── extraccion-documento-v6.md    # current document-OCR prompt
        └── *-v3.md, *-v4.md, extraccion-documento-v5.md
                                          # superseded, kept so older analyses stay auditable
```

`ClaimReport` (the shared input DTO) lives in `common-lib`.

## How to Run

For the full dev workflow (Docker Compose, Ollama vs. Gemini, against Railway's DB) see the root
[`Readme.md`](../Readme.md#desarrollo-local). The module needs Postgres with the schema from `db/`
loaded, and a model provider (Ollama or Gemini) to classify.

```bash
# From project root
mvn spring-boot:run -pl classification-service
```

Service starts at `http://localhost:8082`. Swagger UI: `http://localhost:8082/swagger-ui.html`.

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

## Internal API (module-to-module — this is what `cases-service` calls)

```
POST   /api/v1/claims                        multipart, requires "caseId" — kicks off async analysis
GET    /api/v1/claims/{caseId}               poll for the result (null fields until it finishes)
GET    /api/v1/claims/{caseId}/rule-results  every rule evaluated for the case
POST   /api/v1/claims/{caseId}/decision      persist the analyst's verdict (service-token only)
```

To exercise it end to end, use `docs/postman/Arbiter_Cases_EndToEnd.postman_collection.json`: it
creates a case in `cases-service`, which calls this module for you.

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
              (deterministic)  insurer DB      rules-service  LlmClient       ClipClient
                                                              (Ollama/Gemini) (embedding-service)
```

With the `insurer-db` Spring profile, `InsurerDatabaseAdapter` reads the insurer's DB; without it,
`MockInsurerAdapter` serves made-up policies (and logs a warning at startup).
