# Arbiter

Sistema de gestión inteligente del ciclo de vida de **siniestros** con IA, pensado como plataforma multi-aseguradora. Proyecto Final UTN FRBA (DDSI · K5054 · Grupo 5303).

Foco actual: el **Módulo de Análisis y Clasificación** — clasificación preliminar del siniestro con un LLM local (Ollama + Qwen3-VL) y revisión humana obligatoria.

> Arquitectura, modelo de dominio y decisiones cerradas están documentados en [`CLAUDE.md`](CLAUDE.md).

---

## Requisitos previos

- JDK 21
- Maven 3.9+
- Docker + Docker Compose (para Postgres y Ollama)
- Node 20+ (para el frontend Angular)

---

## Estructura del repositorio

```
arbiter/
├── pom.xml                    # POM padre: versiones y dependencyManagement
├── docker-compose.yml         # Postgres + módulos backend (+ Ollama con el perfil `ollama`)
├── db/                        # Esquema multi-tenant, seed y migraciones (sin Flyway)
├── common-lib/                # Tipos compartidos entre módulos (enums, DTOs, excepciones)
├── classification-service/    # Módulo de Análisis y Clasificación — puerto 8082
├── cases-service/             # Módulo de Expedientes — puerto 8083
├── arbiter-frontend/          # SPA Angular 20 — puerto 4200
├── auth-service/              # Gestión de usuarios (Auth0 + JWT + RBAC) — puerto 8080
├── rules-service/             # Motor de reglas de negocio — puerto 8081
├── reports-service/           # Reportes y estadísticas — puerto 8084
├── embedding-service/         # Sidecar Python (CLIP) para detección de imágenes duplicadas — puerto 8000
├── scripts/                   # Wrappers de arranque y de base de datos (PowerShell)
└── docs/                      # Documentación, colecciones Postman y fixtures de prueba
```

Cada servicio backend Spring Boot es una aplicación independiente que declara `common-lib` como
dependencia local del reactor.

`embedding-service` no es Java/Maven: es un servicio Python chico (`app.py`, puerto 8000) que sirve
embeddings CLIP ViT-B-32 (512 dimensiones). Lo consume `classification-service`
(`EMBEDDING_SERVICE_URL`) para detectar imágenes reutilizadas con pgvector.

---

## Stack

| Tecnología       | Versión / detalle                    |
|------------------|--------------------------------------|
| Java             | 21 (virtual threads)                 |
| Spring Boot      | 4.0.5                                |
| Spring Cloud BOM | 2025.1.1                             |
| PostgreSQL       | 16                                   |
| LLM              | Ollama + `qwen3-vl:8b-instruct` (contexto 32.768); Gemini por Vertex opcional |
| Frontend         | Angular 20                           |
| Lombok           | 1.18.34                              |

---

## Desarrollo local

### Backend (Maven)

Desde la **raíz del proyecto**:

```bash
mvn clean install                              # construye common-lib primero, luego los módulos
mvn spring-boot:run -pl classification-service # corre el módulo de clasificación (8082)
mvn spring-boot:run -pl cases-service          # corre el módulo de expedientes (8083)
mvn -pl classification-service test            # tests del módulo
```

No hay perfil con mocks: cada módulo necesita la base (con el esquema de `db/` cargado) y
`auth-service` necesita Auth0 configurado en el `.env` (ver `.env.example`). Para clasificar,
`classification-service` necesita un modelo: Ollama local o Gemini (ver más abajo).

### Frontend (Angular)

```bash
cd arbiter-frontend
npm install
npm start          # http://localhost:4200
```

### Todo junto (Docker Compose)

Levanta Postgres, el sidecar de embeddings y los módulos backend. Ollama está detrás del perfil
`ollama` (con `COMPOSE_PROFILES=ollama` en el `.env`, o usando `scripts/dev-ollama.ps1`); sin él el
stack arranca igual pero la clasificación falla al primer siniestro.

```bash
docker compose up --build
```

> El contexto de build de cada imagen es **siempre la raíz** del proyecto (el multi-módulo necesita
> el POM padre + `common-lib`). Ver los `Dockerfile` de cada servicio.

### Ollama local vs. Gemini (Vertex)

`classification-service` sirve el modelo con `LLM_PROVIDER` (`ollama`, default, u `gemini`). Los
dos wrappers de `scripts/` levantan todo el stack ya apuntado a uno u otro, sin tocar el `.env`:

```powershell
.\scripts\dev-ollama.ps1   # Ollama local (perfil "ollama": arranca ollama + ollama-init)
.\scripts\dev-gemini.ps1   # Gemini por Vertex — no levanta Ollama
```

`dev-gemini.ps1` necesita, una sola vez por máquina y con una cuenta que tenga permiso sobre el
proyecto de Vertex:

```bash
gcloud auth application-default login
```

Sin login, el script avisa y no levanta nada. Con login, resuelve solo la credencial (ADC) y la
monta de solo lectura vía `docker-compose.gemini.yml` — no hace falta escribir ningún
`docker-compose.override.yml` a mano para esto.

Cualquier flag extra se pasa a `docker compose up`, ej. `.\scripts\dev-gemini.ps1 --build -d`.

### Todo junto, contra la base de Railway

`docker-compose.railway.yml` es la variante que **no** trae su propio Postgres: los módulos
backend, el sidecar de embeddings y el frontend containerizado corren en Docker igual, pero apuntan a la base
compartida de Railway (`DB_URL`/`DB_USER`/`DB_PASSWORD` del `.env` de la raíz). Sin riesgo de
mezclar datos con el Postgres local de `docker-compose.yml`, porque no lo levanta.

```bash
docker compose -f docker-compose.railway.yml up --build -d
```

Reconstruir solo un servicio puntual después de tocar su código:

```bash
docker compose -f docker-compose.railway.yml up --build -d cases-service
```

> Prerequisito: la base de Railway ya tiene que tener el esquema y el seed cargados
> (`scripts/db-railway.ps1 all`). Con `ddl-auto=validate`, si falta algo el contenedor no arranca —
> mirá sus logs, Hibernate dice tabla/columna/tipo. `scripts/db-railway.ps1 check` confirma que el
> esquema está al día sin tocar nada.

> El frontend containerizado publica en el 4200, igual que `ng serve`: levantá uno o el otro
> (`FRONTEND_PORT` mueve el del contenedor).

---

## Estado del proyecto

Los siete módulos están implementados: `common-lib`, `auth-service` (Auth0 + JWT + RBAC),
`rules-service` (motor de reglas), `classification-service`, `cases-service`, `reports-service`
(tablero de métricas, reporte de fraude y de resolución) y `embedding-service`, más el frontend
`arbiter-frontend`.
