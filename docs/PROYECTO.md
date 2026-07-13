# Arbiter — Organización del Proyecto

> La arquitectura, el modelo de dominio y las decisiones cerradas están en [`CLAUDE.md`](../CLAUDE.md).
> Este archivo es solo un mapa de "dónde está cada cosa".

## Estructura de carpetas

```
arbiter/
├── CLAUDE.md                          # Guía de arquitectura y convenciones
├── Readme.md                          # Cómo levantar el proyecto
├── pom.xml                            # POM padre (multi-módulo)
├── docker-compose.yml                 # Postgres + Ollama + módulos backend
│
├── docs/                              # Documentación
│   ├── PROYECTO.md                    # (este archivo)
│   ├── postman/                       # Colecciones Postman + docs de prueba
│   ├── scripts/                       # Scripts de prueba (.http, .ps1)
│   └── siniestros/                    # Docs del módulo de Análisis y Clasificación
│       ├── TESTING.md
│       ├── TEST-CLASIFICACIONES.md
│       ├── MAPEO_TIPOLOGIAS_CLASIFICACIONES.md
│       ├── OLLAMA_STREAMING.md
│       ├── GAPS-FLUJO.md              # Desvíos vs. el flujo aprobado
│       └── diagrama-flujo-clasificacion.pdf
│
├── common-lib/                        # Tipos compartidos entre módulos (enums, DTOs, excepciones)
│
├── classification-service/            # Módulo de Análisis y Clasificación (puerto 8082)
│   ├── src/main/java/.../classification/
│   │   ├── ClassificationServiceApplication.java
│   │   ├── config/                    # AsyncConfig, OllamaConfig/Properties, Swagger, Web
│   │   ├── controllers/               # ClaimController, ClassificationController
│   │   ├── dto/                       # Request/Response locales
│   │   ├── exceptions/                # Excepciones locales + @RestControllerAdvice
│   │   ├── models/
│   │   │   ├── entities/              # ClassificationLog (auditoría inmutable)
│   │   │   └── repositories/          # ClassificationLogRepository
│   │   ├── services/                  # Orchestrator, FastTrackValidator, PromptBuilder, ...
│   │   └── adapters/                  # ClaimClassifier/OllamaAdapter, Rules/InsurerAdapter + mocks
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── prompts/                   # classification-v1.md, extraccion-documento-v1.md
│   └── src/test/java/.../classification/  # Tests (mock por defecto, Ollama real opcional)
│
└── cases-service/                     # Módulo de Expedientes (puerto 8083)
    └── src/main/java/.../cases/       # CaseController, CaseService, ClaimsAnalysisClient, CaseEntity
```

---

## Módulos Maven

| Módulo                   | Responsabilidad                              | Estado            |
|--------------------------|----------------------------------------------|-------------------|
| `common-lib`             | Enums de dominio, DTOs y excepciones compartidas | ✅ Activo      |
| `classification-service` | Análisis y Clasificación (enfoque actual)    | ✅ En desarrollo  |
| `cases-service`          | Gestión de Expedientes                       | ✅ En desarrollo  |
| `arbiter-frontend`       | SPA Angular 20                               | ✅ En desarrollo  |
| `rules-service`          | Motor de Reglas                              | 📋 Scaffold       |
| `auth-service`           | Gestión de Usuarios + Auth0                  | 📋 Scaffold       |
| `reports-service`        | Reportes y Estadísticas                      | 📋 Scaffold       |

`rules`, `auth` y `reports` están scaffoldeados (estructura de paquetes vacía) y comentados en el POM padre.

---

## Cómo navegar

| Quiero…                                   | Dónde                                                        |
|-------------------------------------------|-------------------------------------------------------------|
| Entender la arquitectura                  | [`CLAUDE.md`](../CLAUDE.md) — "Arquitectura"                 |
| Entender el modelo de dominio             | [`CLAUDE.md`](../CLAUDE.md) — "Modelo de dominio"            |
| Ver un ejemplo de denuncia + clasificación| `docs/siniestros/TEST-CLASIFICACIONES.md`                   |
| Ver qué falta vs. el flujo aprobado       | `docs/siniestros/GAPS-FLUJO.md`                             |
| Debuggear el flujo / correr tests         | `docs/siniestros/TESTING.md`                                |
| Probar endpoints con Postman              | `docs/postman/`                                             |
| Correr el módulo localmente               | `classification-service/README.md`                          |

---

## Commits y convenciones

- **Formato:** `tipo(scope): descripción` (ej. `feat(siniestros): agregar endpoint de clasificación`)
- **Tipos:** `feat`, `fix`, `refactor`, `test`, `docs`, `chore`
- **Idioma:** español rioplatense en mensajes y docs; inglés en código y comentarios.

---

## Variables de entorno

En `.env.local` (no versionado). Con el perfil `dev` los adapters externos usan mocks, así que
alcanza con Postgres para correr el flujo completo:

```env
OLLAMA_BASE_URL=http://localhost:11434
DB_URL=jdbc:postgresql://localhost:5432/arbiter
DB_USER=arbiter
DB_PASSWORD=arbiter
# Pendientes cuando se implementen auth/notificaciones:
# AUTH0_DOMAIN, AUTH0_AUDIENCE, SENDGRID_API_KEY, AWS_S3_BUCKET
LOG_LEVEL=INFO
```
