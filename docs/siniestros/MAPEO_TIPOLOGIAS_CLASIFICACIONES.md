# Mapeo: Tipologías BBVA → Clasificaciones del sistema

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

## Mapeo a Clasificaciones del sistema

Importante: **`FAST_TRACK` ya no es una salida posible del LLM.** Se decide antes, de forma
determinística, con `FastTrackValidator` evaluando las reglas de negocio (monto reclamado vs.
suma asegurada, siniestros previos, póliza al día, documentos requeridos). Si el gate determina
Fast Track, el caso va directo al analista para aprobar — **nunca llega al LLM**. Los otros 4
valores (`FALTA_DOCUMENTACION`, `LLM_RECOMIENDA_APROBAR`, `LLM_NO_RECOMIENDA_APROBAR`,
`LLM_SOLICITA_REVISION_MANUAL`) son **recomendaciones no vinculantes** del LLM — el analista
siempre tiene la decisión final, ni siquiera Fast Track resuelve el expediente automáticamente.

### FAST_TRACK ← (gate determinístico, no LLM)
- **Express puro**: ≥6m, sin siniestros previos, documentación completa
- **Daños a Cristales** con presupuesto de recambio
- **Daños a Equipos** con factura + presupuesto de reparación
- Criterios: sin alertas, documentación completa, historial limpio

**Ejemplo**: Cliente 5 años con póliza, pantalla rota, foto + presupuesto.

---

### FALTA_DOCUMENTACION ← (LLM)
- **Documentación Adicional Reducida**: Necesita documentos específicos (factura, presupuesto, etc.)
- **Daños a Equipos** sin presupuesto de reparación
- **Daños a Cristales** sin presupuesto de recambio
- Casos válidos pero incompletos

**Ejemplo**: "Me rompió la pantalla pero no tengo presupuesto todavía." → Pedir presupuesto.

---

### LLM_RECOMIENDA_APROBAR ← (LLM)
- **Hecho consistente y documentado**, sin patrones de alerta, sin exclusiones aplicables
- Casos que no califican para Fast Track determinístico (por monto, historial, o falta de
  documentos requeridos para el gate) pero que, analizados por el LLM, no presentan riesgo

**Ejemplo**: Cliente con denuncia consistente, documentación completa, pero con un siniestro
previo que lo sacó del umbral de Fast Track — el LLM revisa el caso y no encuentra alertas.

---

### LLM_NO_RECOMIENDA_APROBAR ← (LLM)
- **Premio (cuota) adeudado** a la fecha del hecho → Causal de rechazo
- **Múltiples siniestros recientes** (>1 en últimos 2 años, pero <6m antigüedad)
- **Inconsistencias** entre texto de denuncia y documentos (ej. fecha en denuncia ≠ fecha en documento)
- **Hecho potencialmente excluido** por póliza (ej. daño no cubierto, bien fuera del campo visual)
- **Patrón sospechoso**: mismo tipo de bien, múltiples reclamaciones en corto tiempo

**Ejemplo**: Cliente con 3 siniestros en 6 meses, descripción vaga, no precisa ubicación.

---

### LLM_SOLICITA_REVISION_MANUAL ← (LLM)
- **Tipología Urgente**: Incendio, granizo, daños por agua, robos >Anexo II → Siempre a liquidador
- **Tipología Documentación Adicional Amplia**: Casos complejos, no estándar
- **Denuncia fuera de plazo** (>72h del hecho) → Hay que evaluar prescripción y si BBVA acepta
- **Datos faltantes en la denuncia** que impiden clasificar (sin fecha del hecho, sin nro póliza)
- **Ambigüedad clara**: "No sé si me lo robaron o lo perdí"
- **Contexto que requiere interpretación**: Daño durante viaje, conflicto entre coberturas, situación no prevista

**Ejemplo**: "Se incendió el domicilio, se perdió todo, tengo que reclamar a mi póliza de hogar y automóvil."

---

## Reglas de Negocio para Distinguir

### ¿LLM_SOLICITA_REVISION_MANUAL vs FALTA_DOCUMENTACION?

| Factor | FALTA_DOCUMENTACION | LLM_SOLICITA_REVISION_MANUAL |
|--------|---|---|
| **Documentación específica** | Sí, pero clara cuál falta | No claro qué falta |
| **Complejidad del caso** | Baja (rotura, daño simple) | Alta (incendio, múltiples bienes, interpretación) |
| **Tipo de hecho** | Daño/rotura estándar | Urgente, atípico, multicobertura |
| **Información del asegurado** | Completa pero incompleta documentación | Ambigua, contradictoria, sin datos clave |
| **Acción siguiente** | "Enviar presupuesto" | "Escalar a liquidador/especialista" |

**Ejemplo que pasa de FALTA_DOCUMENTACION a LLM_SOLICITA_REVISION_MANUAL:**
- "Me robaron el celular, no tengo la denuncia policial" → FALTA_DOCUMENTACION (pedir denuncia)
- "Se incendió mi casa y me robaron el celular dentro" → LLM_SOLICITA_REVISION_MANUAL (múltiples pólizas, liquidador)

---

### ¿LLM_NO_RECOMIENDA_APROBAR vs LLM_SOLICITA_REVISION_MANUAL?

| Factor | LLM_NO_RECOMIENDA_APROBAR | LLM_SOLICITA_REVISION_MANUAL |
|--------|---|---|
| **Señal de alerta** | Sí (pero clasificable) | Sí (pero no clasificable por el modelo) |
| **Recomendación posible** | "No recomiendo aprobar" | "No sé, que el analista decida sin mi recomendación" |
| **Documentos contradicen** | Sí, claro (fecha, ubicación) | Ambiguo, contexto complejo |
| **Patrón identificable** | Sí (reincidencia, inconsistencia) | No (sin patrón claro) |

**Ejemplo:**
- "Me robaron 3 celulares en 6 meses, ubicaciones vagas" → LLM_NO_RECOMIENDA_APROBAR (patrón sospechoso)
- "Tenía el celular en la mochila de mi auto que se robaron en la playa" → LLM_SOLICITA_REVISION_MANUAL (¿cubre auto? ¿cubre contenido? Ambiguo)

---

## Criterios de Decisión del LLM

Nota: estos criterios solo aplican **después** de que `FastTrackValidator` determinó que el
caso NO califica para Fast Track. Si llegó al LLM, Fast Track ya quedó descartado.

```
SI hecho_es_urgente (incendio, granizo, robo >Anexo II, daño por agua)
  → LLM_SOLICITA_REVISION_MANUAL (siempre a liquidador)

ELSE SI tipologia_es_compleja (Documentación Adicional Amplia)
  → LLM_SOLICITA_REVISION_MANUAL

ELSE SI denuncia_fuera_de_plazo (>72h)
  → LLM_SOLICITA_REVISION_MANUAL (hay que evaluar prescripción)

ELSE SI datos_faltantes_en_denuncia (sin fecha, sin nro póliza)
  → LLM_SOLICITA_REVISION_MANUAL (no se puede clasificar)

ELSE SI premio_adeudado
  → LLM_NO_RECOMIENDA_APROBAR (causal de rechazo)

ELSE SI patron_sospechoso (múltiples siniestros recientes, inconsistencias, patrón de fraude)
  → LLM_NO_RECOMIENDA_APROBAR

ELSE SI hecho_potencialmente_excluido (bien fuera de cobertura, daño no cubierto)
  → LLM_NO_RECOMIENDA_APROBAR

ELSE SI documentacion_incompleta (falta presupuesto, factura, etc.)
  → FALTA_DOCUMENTACION

ELSE SI (documentacion_completa AND sin_alertas AND historial_limpio)
  → LLM_RECOMIENDA_APROBAR

ELSE
  → LLM_SOLICITA_REVISION_MANUAL (cuando hay duda)
```

---

## Distribución esperada de 10 casos

| Tipología BBVA | Escenario | Clasificación esperada | Por qué |
|---|---|---|---|
| Express | Cliente 5 años, sin siniestros previos, pantalla rota + foto + presupuesto | FAST_TRACK | ✓ Gate determinístico, ni llega al LLM |
| Documentación Adicional Reducida | Cliente 3 años, 1 siniestro previo, rotura pantalla, sin presupuesto | FALTA_DOCUMENTACION | Falta presupuesto |
| Daños a Equipos | Laptop rota, con factura y presupuesto de reparación | FAST_TRACK | ✓ Gate determinístico, ni llega al LLM |
| Daños a Cristales | Cristal roto con presupuesto de recambio | FAST_TRACK | ✓ Gate determinístico, ni llega al LLM |
| Urgente | Incendio de vivienda | LLM_SOLICITA_REVISION_MANUAL | → Liquidador |
| Documentación Adicional Amplia | Cliente reclama por daño en viaje (multi-cobertura: auto + hogar + viaje) | LLM_SOLICITA_REVISION_MANUAL | Complejo, multicobertura |
| Plazo | Denuncia 10 días después del hecho | LLM_SOLICITA_REVISION_MANUAL | Fuera de plazo, evaluar prescripción |
| Premio adeudado | Cliente con cuota sin pagar, presenta denuncia | LLM_NO_RECOMIENDA_APROBAR | Causal de rechazo |
| Datos faltantes | "Alguien me robó el celular hace unos días no me acuerdo dónde" | LLM_SOLICITA_REVISION_MANUAL | Sin fecha, sin ubicación |
| Hecho excluido | Póliza de daño a cristal, reclama por pantalla de TV rota | LLM_NO_RECOMIENDA_APROBAR | Cobertura no aplica, posible mala fe |
