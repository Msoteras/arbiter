# siniestros-service — Módulo de Análisis y Clasificación

Clasifica siniestros usando un LLM local (Ollama + Qwen3-VL). El modelo recibe datos estructurados de múltiples fuentes (reglas de negocio, póliza, historial del asegurado) y devuelve una clasificación con factores y nivel de confianza.

## Clasificaciones posibles

| Clasificación | Significado |
|---|---|
| `POTENCIAL_RIESGO` | Inconsistencias, indicadores de fraude, requiere investigación |
| `SIN_RIESGO` | Denuncia consistente, hecho cubierto, sin alertas |
| `FAST_TRACK` | Caso simple y verificable, puede procesarse de forma expedita |

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

### 1. Compilar (sin tests)

Desde la **raíz del proyecto** (`arbiter/`):

```bash
mvn -pl common-lib,siniestros-service -am compile
```

### 2. Tests de integración con Docker (recomendado)

Levanta Ollama en un contenedor, baja el modelo automáticamente y corre los tests:

```bash
docker compose -f docker-compose.test.yml up --exit-code-from siniestros-test
```

> La primera vez tarda varios minutos porque baja `qwen3-vl` (~5 GB).
> Las siguientes corridas son rápidas: el modelo queda en el volumen `ollama_data`.

Para limpiar todo (incluyendo el modelo descargado):

```bash
docker compose -f docker-compose.test.yml down -v
```

### 3. Tests de integración con Ollama local

Si ya tenés Ollama instalado y el modelo descargado:

```bash
# Asegurate de tener el modelo
ollama pull qwen3-vl

# Corré los tests (Ollama tiene que estar corriendo en localhost:11434)
mvn -pl siniestros-service test -Dgroups=integracion
```

Para apuntar a otra URL de Ollama:

```bash
mvn -pl siniestros-service test -Dgroups=integracion -DOLLAMA_BASE_URL=http://otra-ip:11434
```

### 4. Levantar el servicio

```bash
mvn spring-boot:run -pl siniestros-service
```

El servicio arranca en `http://localhost:8082`.

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

Con el servicio corriendo (`mvn spring-boot:run -pl siniestros-service`), hacer un **POST** a:

```
http://localhost:8082/api/v1/clasificaciones
```

**Content-Type**: `application/json`

Body de ejemplo (caso POTENCIAL_RIESGO — reincidente):

```json
{
  "ramo": "Celulares",
  "producto": "Celular Protegido Premium",
  "hechoGenerador": "Robo en vía pública",
  "bienAsegurado": "iPhone 16 Pro Max 256GB - IMEI 353000000000099",
  "aseguradoDni": "30.555.777",
  "polizaNumero": "POL-CEL-2025-099",
  "descripcionLibre": "Me robaron el celular el martes a la noche...",
  "fechaHecho": "2026-06-10T23:00:00",
  "lugarHecho": "Palermo, CABA",
  "adjuntosOCR": ["DENUNCIA POLICIAL Nro 2026/78901..."]
}

```

Hay más ejemplos listos para copiar en `src/main/resources/ejemplos-postman.json`.

> **Importante**: para que funcione, Ollama tiene que estar corriendo con `qwen3-vl`.
> Los datos de póliza, historial y reglas se resuelven con los mocks internos — no necesitás otros módulos levantados.

**Respuesta esperada:**

```json
{
  "clasificacion": "POTENCIAL_RIESGO",
  "factores": [
    "Cuarto siniestro en 8 meses",
    "Descripción imprecisa del hecho",
    "Inconsistencia entre ubicación declarada y denuncia policial"
  ],
  "confianza": 0.85
}
```

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

## Próximos pasos

- [ ] Integración con NOSIS (scoring crediticio) — en evaluación
- [ ] Implementaciones REST reales de `AseguradoraAdapter` y `ReglasAdapter`
- [ ] Swagger/SpringDoc para documentación automática del endpoint
