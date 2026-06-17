# Pruebas de Clasificación — Arbiter

Dos opciones para invocar el módulo de clasificación con los escenarios de test:

## Opción 1: PowerShell script (recomendado para visualización)

Ejecuta todos los escenarios y muestra un resumen en tabla:

```powershell
./test-clasificaciones.ps1
```

### Parámetros

```powershell
# Especificar un endpoint custom
./test-clasificaciones.ps1 -BaseUrl http://custom-host:9000

# Filtrar solo escenarios que matcheen un patrón
./test-clasificaciones.ps1 -Filter "fast-track"
```

### Salida

```
╔════════════════════════════════════════════════════════════════╗
║  PRUEBAS DE CLASIFICACIÓN — Arbiter v1.0                      ║
╠════════════════════════════════════════════════════════════════╣
║  Endpoint: http://localhost:8082/api/v1/clasificaciones
║  Fixtures: 11 escenarios
╚════════════════════════════════════════════════════════════════╝

▶ escenario-fast-track.json ... ✓ SÍ
▶ escenario-posible-riesgo.json ... ✗ NO
...

╔════════════════════════════════════════════════════════════════╗
║  RESUMEN                                                       ║
╠════════════════════════════════════════════════════════════════╣

Esperada             Obtenida             Confianza Factores ✓/✗ Latencia
────────             ────────             ───────── ───────────────────
FAST_TRACK           FAST_TRACK               0.95         3    ✓  245ms
POSIBLE_RIESGO       FAST_TRACK               0.88         2    ✗  312ms

╠════════════════════════════════════════════════════════════════╣
║  Total: 11 | Exitosos: 9 | Fallidos: 2
╚════════════════════════════════════════════════════════════════╝
```

## Opción 2: HTTP client (para testing puntual)

Abre `test-clasificaciones.http` en **IntelliJ IDEA**, **WebStorm**, o **VS Code (con REST Client)**:

1. Abre el archivo
2. Clickea el ▶ (play) al lado de cada request
3. Ver respuesta en el panel de la derecha

Contiene 5 escenarios pre-armados con ejemplos de cada categoría.

## Prereqs

- **Servicio siniestros corriendo**: `mvn spring-boot:run -pl siniestros-service` (puerto 8082)
- **Ollama disponible**: `ollama serve` en http://localhost:11434
- **PowerShell 7+** o **.NET Framework 4.7+** (para el script)

## Flujo de testing

### Rápido (opción 2: 5 request HTTP)
1. Abre `test-clasificaciones.http`
2. Clickea cada request
3. Verifica que la clasificación sea correcta en 5-10 segundos

### Completo (opción 1: todos los fixtures)
1. Ejecuta `./test-clasificaciones.ps1`
2. Espera ~2-3 minutos (11 escenarios × 10-30 seg cada uno)
3. Ve el resumen con tasas de acierto

## Interpretación de resultados

| Clasificación | Significado |
|---|---|
| `FAST_TRACK` | Caso claro, bajo riesgo, puede aprobarse rápido |
| `POSIBLE_RIESGO` | Señales de alerta; requiere análisis adicional |
| `SIN_RIESGO` | Caso rechazado por reglas de negocio |
| `FALTA_DOCUMENTACION` | Documentación requerida no está completa |
| `REQUIERE_ANALISIS_MANUAL` | Ambigüedad o contradicciones; escalado a analista |

## Debugging

Si falla un escenario:

```powershell
# Ver detalles de un escenario específico
./test-clasificaciones.ps1 -Filter "posible-riesgo"

# Ver logs del servicio
mvn spring-boot:run -pl siniestros-service --debug
```

Check Ollama logs si hay timeouts:

```bash
ollama serve  # verifica que el modelo Qwen3-VL está cargado
```

## Notas

- Los fixtures están en `siniestros-service/src/test/resources/fixtures/`
- Cada fixture es un JSON con `nombre`, `clasificacionEsperada`, y el `request` a enviar
- El script enriquece los requests con valores dummy para campos obligatorios (aseguradoDni, polizaNumero, fechaHecho) que no están en los fixtures
- Latencias esperadas: 5-30 segundos por request (depende de carga de Ollama)
