# Mapeo: Tipologías BBVA → Clasificaciones del LLM

## 6 Tipologías BBVA

| Tipología | Criterios | Complejidad |
|---|---|---|
| **Express** | ≥6m antigüedad, sin siniestros previos 2 años, monto en Anexo II | Baja |
| **Documentación Adicional Reducida** | ≥6m antigüedad, hasta 1 siniestro previo 2 años | Baja-Media |
| **Daños a Equipos** | Electromecánicos/portátiles/electrónicos reparables | Baja-Media |
| **Daños a Cristales** | Cristalería con recambio de pieza | Baja |
| **Urgente** | Incendio, granizo, robos >Anexo II, daños por agua → liquidador | Alta |
| **Documentación Adicional Amplia** | Reclamos que no caben en las otras | Alta |

---

## Mapeo a Clasificaciones LLM

### FAST_TRACK ← 
- **Express puro**: ≥6m, sin siniestros previos, documentación completa
- **Daños a Cristales** con presupuesto de recambio
- **Daños a Equipos** con factura + presupuesto de reparación
- Criterios: sin alertas, documentación completa, historial limpio

**Ejemplo**: Cliente 5 años con póliza, pantalla rota, foto + presupuesto.

---

### FALTA_DOCUMENTACION ←
- **Documentación Adicional Reducida**: Necesita documentos específicos (factura, presupuesto, etc.)
- **Daños a Equipos** sin presupuesto de reparación
- **Daños a Cristales** sin presupuesto de recambio
- Casos válidos pero incompletos

**Ejemplo**: "Me rompió la pantalla pero no tengo presupuesto todavía." → Pedir presupuesto.

---

### POTENCIAL_RIESGO ←
- **Premio (cuota) adeudado** a la fecha del hecho → Causal de rechazo
- **Múltiples siniestros recientes** (>1 en últimos 2 años, pero <6m antigüedad)
- **Inconsistencias** entre texto de denuncia y documentos (ej. fecha en denuncia ≠ fecha en documento)
- **Hecho potencialmente excluido** por póliza (ej. daño no cubierto, bien fuera del campo visual)
- **Patrón sospechoso**: mismo tipo de bien, múltiples reclamaciones en corto tiempo

**Ejemplo**: Cliente con 3 siniestros en 6 meses, descripción vaga, no precisa ubicación.

---

### REQUIERE_ANALISIS_MANUAL ←
- **Tipología Urgente**: Incendio, granizo, daños por agua, robos >Anexo II → Siempre a liquidador
- **Tipología Documentación Adicional Amplia**: Casos complejos, no estándar
- **Denuncia fuera de plazo** (>72h del hecho) → Hay que evaluar prescripción y si BBVA acepta
- **Datos faltantes en la denuncia** que impiden clasificar (sin fecha del hecho, sin nro póliza)
- **Ambigüedad clara**: "No sé si me lo robaron o lo perdí"
- **Contexto que requiere interpretación**: Daño durante viaje, conflicto entre coberturas, situación no prevista

**Ejemplo**: "Se incendió el domicilio, se perdió todo, tengo que reclamar a mi póliza de hogar y automóvil."

---

### SIN_RIESGO ←
- **Hecho excluido** claramente por las condiciones de la póliza
- **Bien no amparado** (ej. celular para uso comercial en póliza personal)
- **Cobertura no aplica** por la naturaleza del daño

**Ejemplo**: Cliente con póliza de daño a cristal reclama por pantalla de TV rota (cobertura no cubre electrónica).

---

## Reglas de Negocio para Distinguir

### ¿REQUIERE_ANALISIS_MANUAL vs FALTA_DOCUMENTACION?

| Factor | FALTA_DOCUMENTACION | REQUIERE_ANALISIS_MANUAL |
|--------|---|---|
| **Documentación específica** | Sí, pero clara cuál falta | No claro qué falta |
| **Complejidad del caso** | Baja (rotura, daño simple) | Alta (incendio, múltiples bienes, interpretación) |
| **Tipo de hecho** | Daño/rotura estándar | Urgente, atípico, multicobertura |
| **Información del asegurado** | Completa pero incompleta documentación | Ambigua, contradictoria, sin datos clave |
| **Acción siguiente** | "Enviar presupuesto" | "Escalera a liquidador/especialista" |

**Ejemplo que pasa de FALTA_DOCUMENTACION a REQUIERE_ANALISIS_MANUAL:**
- "Me robaron el celular, no tengo la denuncia policial" → FALTA_DOCUMENTACION (pedir denuncia)
- "Se incendió mi casa y me robaron el celular dentro" → REQUIERE_ANALISIS_MANUAL (múltiples pólizas, liquidador)

---

### ¿POTENCIAL_RIESGO vs REQUIERE_ANALISIS_MANUAL?

| Factor | POTENCIAL_RIESGO | REQUIERE_ANALISIS_MANUAL |
|--------|---|---|
| **Señal de alerta** | Sí (pero clasificable) | Sí (pero no clasificable por el modelo) |
| **Decisión posible** | "Rechazar" o "Investigar" | "No sé, que analista decida" |
| **Documentos contradicen** | Sí, claro (fecha, ubicación) | Ambiguo, contexto complejo |
| **Patrón identificable** | Sí (reincidencia, inconsistencia) | No (sin patrón claro) |

**Ejemplo:**
- "Me robaron 3 celulares en 6 meses, ubicaciones vagas" → POTENCIAL_RIESGO (patrón sospechoso, rechazar)
- "Tenía el celular en la mochila de mi auto que se robaron en la playa" → REQUIERE_ANALISIS_MANUAL (¿cubre auto? ¿cubre contenido? Ambiguo)

---

## Criterios de Decisión del LLM

```
SI hecho_es_urgente (incendio, granizo, robo >Anexo II, daño por agua)
  → REQUIERE_ANALISIS_MANUAL (siempre a liquidador)

ELSE SI tipologia_es_compleja (Documentación Adicional Amplia)
  → REQUIERE_ANALISIS_MANUAL

ELSE SI denuncia_fuera_de_plazo (>72h)
  → REQUIERE_ANALISIS_MANUAL (hay que evaluar prescripción)

ELSE SI datos_faltantes_en_denuncia (sin fecha, sin nro póliza)
  → REQUIERE_ANALISIS_MANUAL (no se puede clasificar)

ELSE SI premio_adeudado
  → POTENCIAL_RIESGO (causal de rechazo)

ELSE SI patron_sospechoso (múltiples siniestros recientes, inconsistencias, patrón de fraude)
  → POTENCIAL_RIESGO

ELSE SI hecho_potencialmente_excluido (bien fuera de cobertura, daño no cubierto)
  → POTENCIAL_RIESGO

ELSE SI documentacion_incompleta (falta presupuesto, factura, etc.)
  → FALTA_DOCUMENTACION

ELSE SI hecho_excluido_claramente
  → SIN_RIESGO

ELSE SI (documentacion_completa AND sin_alertas AND historial_limpio)
  → FAST_TRACK

ELSE
  → REQUIERE_ANALISIS_MANUAL (cuando hay duda)
```

---

## Distribución esperada de 10 casos

| Tipología BBVA | Escenario | Clasificación esperada | Por qué |
|---|---|---|---|
| Express | Cliente 5 años, sin siniestros previos, pantalla rota + foto + presupuesto | FAST_TRACK | ✓ Expedito |
| Documentación Adicional Reducida | Cliente 3 años, 1 siniestro previo, rotura pantalla, sin presupuesto | FALTA_DOCUMENTACION | Falta presupuesto |
| Daños a Equipos | Laptop rota, con factura y presupuesto de reparación | FAST_TRACK | ✓ Documentado |
| Daños a Cristales | Cristal roto con presupuesto de recambio | FAST_TRACK | ✓ Expedito |
| Urgente | Incendio de vivienda | REQUIERE_ANALISIS_MANUAL | → Liquidador |
| Documentación Adicional Amplia | Cliente reclama por daño en viaje (multi-cobertura: auto + hogar + viaje) | REQUIERE_ANALISIS_MANUAL | Complejo, multicobertura |
| Plazo | Denuncia 10 días después del hecho | REQUIERE_ANALISIS_MANUAL | Fuera de plazo, evaluar prescripción |
| Premio adeudado | Cliente con cuota sin pagar, presenta denuncia | POTENCIAL_RIESGO | Causal de rechazo |
| Datos faltantes | "Alguien me robó el celular hace unos días no me acuerdo dónde" | REQUIERE_ANALISIS_MANUAL | Sin fecha, sin ubicación |
| Hecho excluido | Póliza de daño a cristal, reclama por pantalla de TV rota | SIN_RIESGO | Cobertura no aplica |
