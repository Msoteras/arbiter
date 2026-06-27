# Arbiter — Organización del Proyecto

## Estructura de carpetas

```
arbiter/
├── CLAUDE.md                          # Guía para Claude (instrucciones de arquitectura)
├── pom.xml                            # POM padre (multi-módulo)
│
├── docs/                              # Documentación
│   ├── PROYECTO.md                    # (este archivo)
│   ├── api/                           # Documentación de API REST
│   │   └── endpoints.md
│   └── siniestros/                    # Docs del módulo de Análisis y Clasificación
│       ├── TESTING.md                 # Guía de testing
│       ├── MAPEO_TIPOLOGIAS_CLASIFICACIONES.md
│       └── TEST-CLASIFICACIONES.md
│
├── postman/                           # Colecciones Postman
│   └── Arbiter_Claims_Classification.postman_collection.json
│
├── scripts/                           # Scripts auxiliares
│   ├── test-clasificaciones.http      # Tests REST (HTTP Client de IntelliJ)
│   └── test-clasificaciones.ps1       # Tests PowerShell
│
├── common-lib/                        # Librería compartida
│   ├── src/main/java/ar/edu/utn/frba/arbiter/common/
│   │   ├── enums/                     # Domain enums (Classification, etc.)
│   │   ├── dto/                       # DTOs compartidos entre módulos
│   │   └── exceptions/                # Excepciones base
│   └── pom.xml
│
└── classification-service/                # Módulo de Análisis y Clasificación
    ├── src/main/java/ar/edu/utn/frba/arbiter/classification/
    │   ├── ClassificationServiceApplication.java
    │   ├── config/                    # @Configuration, AsyncConfig, OllamaProperties
    │   ├── controllers/               # REST endpoints
    │   ├── dto/                       # Request/Response (DTO locales)
    │   ├── exceptions/                # Excepciones locales + @ControllerAdvice
    │   ├── models/
    │   │   ├── entities/              # @Entity JPA: ClassificationLog (auditoría inmutable)
    │   │   └── repositories/          # Spring Data JPA: ClassificationLogRepository
    │   ├── services/                  # Lógica de negocio
    │   │   ├── ClassificationJob.java       # Async wrapper con @Async (testeo aislado)
    │   │   ├── ClassificationOrchestrator.java # Orquestación: gate Fast Track + LLM fallback
    │   │   ├── PromptBuilder.java          # Construcción de prompts
    │   │   ├── FastTrackValidator.java     # Gate determinístico de Fast Track
    │   │   └── ClassificationResultsService.java
    │   └── adapters/                  # Integraciones externas
    │       ├── ClaimClassifier.java     # Interface para el LLM
    │       ├── OllamaAdapter.java           # Implementación real (Ollama)
    │       ├── MockClassifier.java          # Mock para dev/test
    │       ├── RulesAdapter.java           # Consulta rules-service
    │       └── InsurerAdapter.java      # Consulta BD aseguradora
    │
    ├── src/main/resources/
    │   ├── application.yml            # Configuración común (datasource + JPA, ddl-auto=update)
    │   ├── application-dev.yml        # Perfil dev (mock)
    │   ├── application-test.yml       # Perfil test (mocks)
    │   └── prompts/
    │       └── clasificacion-v1.md    # Plantilla del prompt
    │
    ├── src/test/java/ar/edu/utn/frba/arbiter/classification/
    │   └── services/
    │       ├── ClassificationOrchestratorIntegrationTest.java  # Tests con mock (default)
    │       └── ClassificationOllamaIntegrationTest.java        # Tests con Ollama real (optional)
    │
    ├── pom.xml
    ├── Dockerfile                     # Multi-stage build
    └── README.md                      # README del módulo
```

---

## Módulos Maven

| Módulo               | Responsabilidad                     | Estado        |
|----------------------|-------------------------------------|---------------|
| `common-lib`         | DTOs compartidos, enums, excepciones | ✅ Activo     |
| `classification-service` | Análisis y Clasificación (enfoque actual) | ✅ En desarrollo |
| `rules-service`     | Motor de Reglas                     | 📋 Planeado   |
| `cases-service`| Gestión de Expedientes              | 📋 Planeado   |
| `reports-service`   | Reportes y Estadísticas             | 📋 Planeado   |
| `auth-service`       | Gestión de Usuarios + Auth0         | 📋 Planeado   |
| `arbiter-frontend`   | SPA React 19                        | 📋 Planeado   |

---

## Cómo navegar

### Quiero entender la arquitectura
→ Lee `CLAUDE.md` (sección "Arquitectura — leer antes de tocar nada")

### Quiero entender el modelo de dominio
→ Lee `CLAUDE.md` (sección "Modelo de dominio — vocabulario")

### Quiero ver un ejemplo de denuncia + clasificación
→ Lee `docs/siniestros/TEST-CLASIFICACIONES.md`

### Quiero debuggear el flujo de clasificación
→ Lee `docs/siniestros/TESTING.md`

### Quiero correr tests
→ Lee `docs/siniestros/TESTING.md`

### Quiero probar endpoints con Postman
→ Importá `postman/Arbiter_Claims_Classification.postman_collection.json`

### Quiero correr el módulo localmente
→ Lee `classification-service/README.md`

### Quiero ver el flujo de capas (MVC)
→ Explorá `classification-service/src/main/java/ar/edu/utn/frba/arbiter/classification/`

---

## Commits y convenciones

- **Formato de commit:** `tipo(scope): descripción` (ej. `feat(siniestros): agregar endpoint de clasificación`)
- **Tipos:** `feat`, `fix`, `refactor`, `test`, `docs`, `chore`
- **Scope:** módulo o concepto afectado (ej. `siniestros`, `common-lib`)
- **Idioma:** español rioplatense en mensajes, inglés en código

### Ejemplo
```bash
git commit -m "feat(siniestros): implementar ClassificationJob asincrónico con reintentos"
```

---

## Variables de entorno

En `.env.local` (no versionado):
```env
# Ollama
OLLAMA_BASE_URL=http://localhost:11434

# Auth0
AUTH0_DOMAIN=your-tenant.auth0.com
AUTH0_AUDIENCE=your-api-identifier
AUTH0_CLIENT_ID=your-client-id
AUTH0_CLIENT_SECRET=your-client-secret

# Base de datos
DB_URL=jdbc:postgresql://localhost:5432/arbiter
DB_USER=postgres
DB_PASSWORD=postgres

# AWS S3 (adjuntos)
AWS_S3_BUCKET=arbiter-adjuntos-dev
AWS_REGION=us-east-1

# SendGrid (email)
SENDGRID_API_KEY=sg_xxxxx

# Logging
LOG_LEVEL=INFO
```

---

## Próximos pasos

1. ✅ **Módulo de Análisis y Clasificación** — armado base (Controllers, Services, Adapters)
2. ⏳ **Flujo async mejorado** — persistencia de clasificación en BD (tablas `siniestro`, `clasificacion_log`)
3. ⏳ **Endpoints de catálogo** en `rules-service` (productos, ramos, hechos generadores)
4. ⏳ **Frontend** — wizard de alta de denuncia + bandeja del analista
5. ⏳ **Motor de Reglas** — configuración dinámica por aseguradora

---

## Links rápidos

- 📖 **Documentación de arquitectura aprobada:** `CLAUDE.md`
- 🧪 **Guía de testing:** `docs/siniestros/TESTING.md`
- 📋 **Casos de prueba:** `docs/siniestros/TEST-CLASIFICACIONES.md`
- 🔗 **Postman:** `postman/Arbiter_Claims_Classification.postman_collection.json`
- 🛠️ **README del módulo:** `classification-service/README.md`
