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
├── docker-compose.yml         # Postgres + Ollama + módulos backend
├── common-lib/                # Tipos compartidos entre módulos (enums, DTOs, excepciones)
├── classification-service/    # Módulo de Análisis y Clasificación — puerto 8082
├── cases-service/             # Módulo de Expedientes — puerto 8083
├── arbiter-frontend/          # SPA Angular 20 — puerto 4200
├── auth-service/              # Gestión de usuarios (Auth0 + JWT + RBAC) — puerto 8080
├── rules-service/             # Motor de reglas de negocio — puerto 8081
├── reports-service/           # Reportes y estadísticas (scaffold) — puerto 8084
└── docs/                      # Documentación, colecciones Postman y scripts
```

Cada servicio backend es una aplicación Spring Boot independiente que declara `common-lib` como
dependencia local del reactor. Los 6 módulos backend están activos en el POM padre; `reports-service`
es el único que sigue siendo scaffold (config multi-tenant + la entidad `Metric`, sin controllers ni
servicios todavía).

---

## Stack

| Tecnología       | Versión / detalle                    |
|------------------|--------------------------------------|
| Java             | 21 (virtual threads)                 |
| Spring Boot      | 4.0.5                                |
| Spring Cloud BOM | 2025.1.1                             |
| PostgreSQL       | 16                                   |
| LLM              | Ollama + Qwen3-VL (contexto 32.768)  |
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

Con el perfil `dev` (por defecto) los adapters externos (Ollama, aseguradora, reglas) usan mocks,
así que el flujo completo corre sin Ollama prendido.

### Frontend (Angular)

```bash
cd arbiter-frontend
npm install
npm start          # http://localhost:4200
```

### Todo junto (Docker Compose)

Levanta Postgres, Ollama (con el modelo pre-descargado) y los módulos backend:

```bash
docker compose up --build
```

> El contexto de build de cada imagen es **siempre la raíz** del proyecto (el multi-módulo necesita
> el POM padre + `common-lib`). Ver los `Dockerfile` de cada servicio.

### Todo junto, contra la base de Railway

`docker-compose.railway.yml` es la variante que **no** trae su propio Postgres: los módulos
backend + Ollama + el sidecar de embeddings corren en Docker igual, pero apuntan a la base
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

---

## Estado del proyecto

Implementados: `classification-service`, `cases-service`, `common-lib`, `arbiter-frontend`,
`auth-service` (Auth0 + JWT + RBAC), `rules-service` (motor de reglas de negocio).
Scaffold pendiente de implementación: `reports-service`.
