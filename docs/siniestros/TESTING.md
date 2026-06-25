# Testing — Módulo de Análisis y Clasificación

## Estructura de tests

El módulo tiene dos tipos de tests:

### 1. Tests unitarios con mock del LLM (recomendado)
**Archivo:** `ClasificacionOrchestratorIntegrationTest.java`

- ✅ **Corre sin dependencias externas** (sin Ollama)
- ✅ **Rápido** (~2-3 segundos)
- ✅ **Determinístico** (resultados fijos via mock)
- ✅ **Válido para CI/CD**

```bash
mvn -pl siniestros-service test -Dtest=ClasificacionOrchestratorIntegrationTest
```

**Qué valida:**
- Flujo de orquestación (consulta póliza, historial, reglas)
- Estructura del request al LLM
- Manejo de respuestas esperadas
- Logging + traceabilidad

---

### 2. Tests de integración con Ollama real (opcional)
**Archivo:** `ClasificacionOllamaIntegrationTest.java`

- ⚠️ **Requiere Ollama corriendo** con `qwen3-vl`
- ⏱️ **Lento** (~15-30 segundos por test)
- ✅ **Valida respuesta real del modelo**
- ❌ **No válido para CI/CD automático** (es manual/en dev)

```bash
# Requiere: ollama serve
mvn -pl siniestros-service test -Dgroups=ollama -Dtest=ClasificacionOllamaIntegrationTest
```

O simplemente: `mvn -pl siniestros-service test` (se salta automáticamente si Ollama no está disponible).

**Qué valida:**
- Integración real con Ollama
- Formato de salida del modelo
- Latencia de respuesta

---

## Cómo correr tests

### Opción 1: Tests unitarios (default)
```bash
# Todos los tests unitarios del módulo
mvn -pl siniestros-service test

# Solo tests unitarios
mvn -pl siniestros-service test -Dtest=ClasificacionOrchestrator*
```

### Opción 2: Tests con Ollama real
```bash
# Primero: levantá Ollama
ollama serve

# En otra terminal:
mvn -pl siniestros-service test -Dgroups=ollama
```

### Opción 3: Todo desde la raíz
```bash
# Todos los tests de todos los módulos
mvn clean test
```

---

## Mock del classifier

Los tests unitarios mockan `SiniestroClassifier` con respuestas fijas:

```java
@MockBean
private SiniestroClassifier classifierMock;

// En cada test:
when(classifierMock.clasificar(any(ClasificacionRequest.class)))
    .thenReturn(expectedResponse);
```

Para agregar casos nuevos, simplemente:
1. Copia un test existente
2. Crea un nuevo `DenunciaSiniestro` con los datos del caso
3. Mocka la respuesta esperada
4. Agrega asserts

---

## Debugging de tests

Si necesitás debuggear un test:

```bash
# Run en modo debug (espera en puerto 5005)
mvn -pl siniestros-service test -Dtest=ClasificacionOrchestratorIntegrationTest -Dmaven.surefire.debug
```

Luego conectá tu IDE con debugger en `localhost:5005`.

---

## Validación pre-commit

Antes de hacer push:
```bash
mvn clean test
```

Si falla, checkea:
- Logs en `target/surefire-reports/`
- Mock del adapter está activo (perfil `test` en `@ActiveProfiles`)
- Variables de entorno correctas
