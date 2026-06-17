# siniestros-service — Módulo de Análisis y Clasificación

Clasifica siniestros usando un LLM local (Ollama + Qwen3-VL). El modelo recibe datos estructurados de múltiples fuentes (reglas de negocio, póliza, historial del asegurado) y devuelve una clasificación con factores y nivel de confianza.

## Clasificaciones posibles

| Clasificación | Significado |
|---|---|
| `FAST_TRACK` | Caso simple, documentación completa, historial limpio → puede procesarse expeditivamente |
| `FALTA_DOCUMENTACION` | Caso válido pero incompleto → requiere documentos específicos del asegurado |
| `POTENCIAL_RIESGO` | Inconsistencias, patrón sospechoso, múltiples siniestros recientes → requiere investigación |
| `REQUIERE_ANALISIS_MANUAL` | Ambigüedad, contexto complejo, datos faltantes → escalada a analista especialista |

## Requisitos

- **Java 21**
- **Maven 3.9+**
- **Docker** y **Docker Compose** (para correr Ollama en contenedor)
- Puerto **8082** libre (servidor Spring Boot)
- Puerto **11434** libre (Ollama)

## Estructura del módulo

```
siniestros-service/
├── src/main/java/.../siniestros/
│   ├── adapters/
│   │   ├── SiniestroClassifier.java      # interfaz del clasificador
│   │   └── OllamaAdapter.java            # implementación contra Ollama
│   ├── config/
│   │   ├── OllamaConfig.java             # bean RestClient para Ollama
│   │   └── OllamaProperties.java         # propiedades (URL, modelo, versión prompt)
│   ├── dto/
│   │   ├── ClasificacionRequest.java     # entrada al clasificador
│   │   └── ClasificacionResponse.java    # salida (clasificación + factores + confianza)
│   └── exceptions/
│       └── ClasificacionInvalidaException.java
├── src/main/resources/
│   ├── application.yml
│   └── prompts/
│       └── clasificacion-v1.md           # plantilla del prompt (versionada)
└── src/test/
    ├── java/.../adapters/
    │   ├── OllamaAdapterIntegrationTest.java       # test básico contra Ollama
    │   ├── OllamaClasificacionEscenariosTest.java  # tests parametrizados con fixtures
    │   └── MockSiniestroClassifier.java             # mock para tests unitarios
    └── resources/fixtures/
        ├── escenario-potencial-riesgo.json   # reincidente, inconsistencias
        ├── escenario-sin-riesgo.json         # primer siniestro, denuncia sólida
        └── escenario-fast-track.json         # rotura de pantalla, simple y verificable
```

## Cómo correr

### 🚀 Opción 1: Todo en Docker (recomendado)

Levanta Ollama + siniestros-service en contenedores:

```bash
# Desde la raíz del proyecto
docker-compose up -d --build
```

**Primera vez:** ~2-3 min (compila Java + descarga modelo Qwen3-VL ~10GB)
**Siguientes veces:** ~30s (usa cache)

Verifica logs:
```bash
docker-compose logs -f siniestros-service
```

Cuando veas `Started SiniestrosServiceApplication` → listo en `http://localhost:8082`

Detener:
```bash
docker-compose down
```

Limpiar todo (incluyendo modelo):
```bash
docker-compose down -v
```

---

### 🧪 Opción 2: Tests parametrizados con Docker

Compila, levanta Ollama, corre 11 escenarios parametrizados en serie (no paralelo):

```bash
docker compose -f docker-compose.test.yml up --exit-code-from siniestros-test
```

Espera ~3-5 min (cada escenario tarda 10-30s en Ollama).

---

### 💻 Opción 3: Dev local con Ollama

Si tenés Ollama instalado localmente:

```bash
# Terminal 1: Ollama
ollama serve

# Terminal 2: Descarga modelo (primera vez)
ollama pull qwen3-vl

# Terminal 3: Servicio Spring Boot
mvn spring-boot:run -pl siniestros-service
```

El servicio arranca en `http://localhost:8082`

## Variables de entorno

| Variable | Default | Descripción |
|---|---|---|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | URL del servidor Ollama |
| `OLLAMA_MODEL` | `qwen3-vl` | Modelo a usar para clasificación |

## Fixtures de test

Los escenarios están en `src/test/resources/fixtures/`. Cada JSON tiene:

```json
{
  "nombre": "Descripción del escenario",
  "clasificacionEsperada": "POTENCIAL_RIESGO | SIN_RIESGO | FAST_TRACK",
  "request": {
    "ramo": "...",
    "producto": "...",
    "hechoGenerador": "...",
    "bienAsegurado": "...",
    "descripcionLibre": "...",
    "adjuntosOCR": ["..."],
    "reglasAseguradora": "...",
    "historialAsegurado": "..."
  }
}
```

Para agregar un escenario nuevo: crear un JSON con la misma estructura en `fixtures/` y agregarlo al `@ValueSource` en `OllamaClasificacionEscenariosTest`.

## Probar desde Postman

### 📥 Importar colección

1. Abre **Postman**
2. **Collections** → **Import**
3. Selecciona: `Arbiter_Siniestros_Clasificacion.postman_collection.json`
4. ✅ Tenés 10 requests + 1 endpoint de resultados listos

### 🔄 Flujo completo

**Endpoint ASINCRÓNICO** (recomendado para ver resultados en archivo):

```
POST http://localhost:8082/api/v1/siniestros
```

- Devuelve **202 Accepted** inmediatamente
- Job procesa en background (~10-30 seg por request)
- Escribe resultado en archivo `./resultados-clasificaciones.md`

**Endpoint SINCRÓNICO** (si querés respuesta inmediata):

```
POST http://localhost:8082/api/v1/clasificaciones
```

- Devuelve **200 OK** con clasificación (pero bloquea hasta 30s)

**Ver resultados acumulados:**

```
GET http://localhost:8082/api/v1/siniestros/resultados
```

- Devuelve tabla markdown con todos los siniestros procesados

### 📊 Workflow sugerido

1. Ejecuta los 10 requests POST a `/api/v1/siniestros` en ráfaga
2. Espera ~30 seg a que terminen los últimos
3. Ejecuta `GET /resultados` para ver la tabla completa
4. Verifica clasificaciones esperadas vs obtenidas

## Arquitectura del flujo de clasificación

```
Denuncia (frontend/Postman)
    │
    ▼
ClasificacionController ──► ClasificacionOrquestador
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
            AseguradoraAdapter  ReglasAdapter   SiniestroClassifier
            (póliza+historial)  (reglas ramo)   (Ollama/LLM)
                    │               │               │
                    ▼               ▼               ▼
              Mock / REST      Mock / REST     OllamaAdapter
              (BD aseguradora) (reglas-service) (qwen3-vl)
```

En dev/test se usan los mocks (`MockAseguradoraAdapter`, `MockReglasAdapter`).
En producción se reemplazarán por implementaciones REST reales.

## ✨ Mejoras recientes (sesión 16/06/2026)

- ✅ **Reintentos en ClasificacionJob** — `@Retryable` con backoff exponencial (2s → 4s → 8s)
- ✅ **PromptBuilder** — patrón builder para construir prompts, limpia 50+ líneas de formato
- ✅ **@Execution(SAME_THREAD)** en tests — evita que Ollama colapse por inferencias paralelas
- ✅ **Colección Postman** — 10 escenarios parametrizados + endpoint de resultados
- ✅ **Mapeo BBVA** — alineación de tipologías de BBVA con clasificaciones del LLM

## Próximos pasos

- [ ] Tabla `clasificacion_log` en BD — auditoría inmutable (Disposición SSN 2/2023)
- [ ] Persistencia de `Siniestro` en BD en lugar de `AtomicLong` (IDs volátiles)
- [ ] Circuit breaker Resilience4j para Ollama
- [ ] Mock Classifier para perfil `dev` (tests sin Ollama)
- [ ] Integración con NOSIS (scoring crediticio) — en evaluación
- [ ] Implementaciones REST reales de `AseguradoraAdapter` y `ReglasAdapter`
