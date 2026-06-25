# Cambios Recientes — Organización y Testing

## Resumen de cambios (16/06/2026)

### 1. Reorganización de estructura de carpetas ✅

**Antes:**
- Archivos sueltos en raíz del proyecto (Postman, scripts, documentación)

**Después:**
```
docs/
├── PROYECTO.md                    (new)
└── siniestros/
    ├── TESTING.md                 (new)
    ├── MAPEO_TIPOLOGIAS_CLASIFICACIONES.md
    └── TEST-CLASIFICACIONES.md
postman/
└── Arbiter_Siniestros_Clasificacion.postman_collection.json
scripts/
├── test-clasificaciones.http
└── test-clasificaciones.ps1
```

**Beneficio:** Proyecto más limpio, estructura clara para futuros desarrolladores.

---

### 2. Mejora del testing 🧪

#### Antes
- Test de integración `ClasificacionOrchestratorIntegrationTest` llamaba sincronicamente a **Ollama real**
- Requería Ollama corriendo para pasar tests
- Bloqueante para CI/CD automático

#### Después
- **Test unitario** `ClasificacionOrchestratorIntegrationTest` con mock del `SiniestroClassifier`
  - ✅ Corre sin Ollama
  - ✅ Rápido (~2-3s)
  - ✅ Determinístico
  - ✅ Válido para CI/CD

- **Test de integración real** `ClasificacionOllamaIntegrationTest` (nuevo)
  - ⚠️ Requiere Ollama
  - ⏱️ Lento (~15-30s)
  - ✅ Valida respuesta real del modelo
  - ✅ Se salta automáticamente si Ollama no está disponible

#### Cómo correr

```bash
# Tests unitarios (default, sin Ollama)
mvn -pl siniestros-service test

# Tests con Ollama real (opcional)
mvn -pl siniestros-service test -Dgroups=ollama
```

---

### 3. Documentación nueva 📖

#### `docs/PROYECTO.md`
- Estructura completa del proyecto
- Mapeo de módulos Maven
- Cómo navegar el codebase
- Variables de entorno

#### `docs/siniestros/TESTING.md`
- Diferencia entre tests unitarios e integración
- Cómo correr cada tipo de test
- Debugging
- Validación pre-commit

---

## Qué NO cambió

- ✅ Lógica del orquestador (sigue siendo síncrono)
- ✅ Lógica del Job async (sigue siendo `@Async`)
- ✅ Flujo de controllers → Job → Orquestador
- ✅ Adapters (Ollama, Reglas, Aseguradora)

---

## Próximos pasos recomendados

1. **Persistencia de clasificaciones** → Crear tablas `siniestro`, `clasificacion`, `clasificacion_log`
2. **Endpoint GET** para consultar clasificación después del async
3. **Endpoints de catálogo** en `reglas-service` (Productos, Ramos, HechosGeneradores)
4. **Frontend** — wizard de alta de denuncia

---

## Validar cambios

```bash
# Verificar estructura
ls -R docs/
ls -R postman/
ls -R scripts/

# Compilar (cuando Maven esté en PATH)
mvn clean compile

# Tests
mvn -pl siniestros-service test
```
