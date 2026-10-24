# Baches del DER

Lo que falta editar en el DER (`docs/arbiter der.mdj`) para que coincida con lo implementado
(`db/init-multitenant.sql`). **No son historias de desarrollo**: son correcciones al propio DER, y
quedan afuera del backlog de Trello por eso.

El DER sigue siendo la fuente de verdad (`CLAUDE.md`). Este documento no propone desvíos: es la
lista de qué hay que corregirle. Cuando una entrada queda aplicada en el `.mdj`, se borra de acá.

**Última revisión: 24/10/2026, duodécima pasada.** No queda nada abierto.

**Revisado el 16/10/2026, novena pasada** contra el `.mdj` editado ese día y contra
`db/init-multitenant.sql`. Salieron las entradas 5, 6, 8, 9, 13 (`analisis_llm.fecha_analisis`), 16,
17, 22, 23, 25, 26, 27 y 32, que ya están en el diagrama. Las entradas 7, 10, 11, 12, 15, 18, 21 y 31
quedaron a medias y dicen solo lo que falta. Se sumó la 35. Las demás conservan su número.

**Décima pasada, el mismo 16/10:** se cruzó cada columna marcada como FK contra las líneas del
diagrama, y cada línea contra el esquema. Salieron las entradas 36, 37 y 38, y se corrigieron la 10
y la 15.

**Undécima pasada, 17/10:** contra el `.mdj` guardado ese día. `rama` pasó a llamarse `ramo` en el
diagrama. Salieron las entradas 21, 28 (`shedlock` no va) y 37: `atributo_documento`,
`tipo_documento`, `ramo_tipo_documento`, `proveedor_externo_ramo` y la línea `usuario` — `asegurado`
ya están, y la línea repetida ya no. En el `.mdj` de las 17:19 ya no está `metrica`,
`ramo_tipo_documento.rama_id` pasó a `ramo_id` y existen las cuatro entidades de métricas. En el de las 19:35, `ramo` (las
dos) y `hecho_generador` ya tienen el borde de las tablas por aseguradora (sale la entrada 11), y
`reiniciado_por` está marcada como FK. El 18/10 se cerró la entrada 13: `expediente.destino` se
borró del esquema y de la entidad (`2026-10-18-borrar-destino-de-expediente.sql`), y el DER ya no la
tenía. Salieron también la 7, la 10, la 30–31 y la 33, que ya están en el diagrama. Se cerró la 35 (decidido el 18/10): las tres
líneas desfasadas quedan como están, porque se ven entre las entidades correctas. La de `usuario` —
`referente_aseguradora` muestra `1—0..*` y no `1—0..1`: comparte la relación del modelo con
`configuracion_scoring` y `regla_aseguradora`, y corregirla exige redibujarla. En el `.mdj` de las 11:34 salieron la 15 y la 38, y cuatro filas de la 36. En el de las 11:56 salió el resto de la 36: no queda nada para
editar en el `.mdj`. Salieron también las entradas de notas de texto (4, 12, 18 y
34): el DER no las lleva. Se cerraron dos decisiones:

- **14 · `aseguradora.logo`:** se queda en el DER. Falta en el esquema, y se implementa con la
  historia "Logo de la aseguradora".
- **19 · `atributo_bien`:** no va al DER. Es una tabla de la BD de la aseguradora, y el DER dibuja
  solo las de Arbiter. Su dato ya queda persistido en Arbiter: cada clasificación lo congela en
  `poliza_consultada.payload_bd_aseguradora`.

**Duodécima pasada, 23/10:** contra el `.mdj` de la rama `feature/fix-der` (al día con `develop`) y
contra `db/init-multitenant.sql`. Se cruzó cada FK del esquema con su línea dibujada, mirando qué
muestra cada extremo. Salieron siete entradas, y quedaron aplicadas el mismo día:

- **40:** `logo_aseguradora`, tabla nueva del esquema común (`2026-10-22-logo-de-la-aseguradora.sql`).
  Sale `aseguradora.logo`, y la decisión 14 ya no vale.
- **41:** la línea `usuario` — `referente_aseguradora` tiene relación propia y muestra `1—0..1`.
- **42:** `permiso` — `rol_permiso`, `expediente` — `analisis_llm` y `analisis_documento` —
  `hallazgo_visual_documento` tenían la cardinalidad al revés.
- **43:** cuatro cardinalidades no coincidían con el esquema (`analisis_riesgo_expediente`,
  `atribucion_liquidacion`, `clasificacion_expediente` y `resultado_regla`).
- **44:** `rol.nombre` sin Unique, `usuario.email` con Unique, `resultado_regla.resultado` en
  VARCHAR(20), `reinicio_puesta_en_marcha.id` en SMALLINT, y `metrica_diaria_ingreso` sin la columna
  vacía.
- **45:** doce líneas de FK que admite nulo pasaron a `0..1` del lado del padre.
- **46:** `rama_id` pasó a `ramo_id` en las cinco entidades que lo tenían.

Las dos líneas desfasadas que quedan (`usuario` — `configuracion_scoring` y `poliza_consultada` —
`liquidacion`) siguen como se decidió el 18/10.

El 24/10 se cerró la 20. La simplificación del modelo de métricas no llegó, y Railway tiene las
cuatro tablas igual que el esquema en los tres esquemas de aseguradora (consultado en modo
lectura). Se cargaron las columnas de las tres `metrica_diaria_*`, se dibujaron `metrica_dia` y
`hecho_generador` 1—0..* contra cada una (con una segunda vista de `hecho_generador` al lado), y
`metrica` salió del modelo.

**Criterios que valen para todas las entradas:**

- **Cada línea se compara dibujo contra modelo.** Una línea que se ve entre dos entidades pero cuya
  relación une otras cuenta como faltante.
- **`shedlock` no va en el DER** (decidido el 17/10): es una tabla de la librería ShedLock, sin dato de
  negocio.
- **Solo cuenta lo que se ve en el diagrama.** Las relaciones que quedaron en el explorador del
  modelo sin línea dibujada no son baches.
- **UNIQUE en una columna FK:** no se marca. La cardinalidad 1:1 va en la relación del diagrama (el
  extremo con `0..1` en vez de `0..*`).
- **FK que admite nulo** (decidido el 23/10): el extremo del padre lleva `0..1`, no `1`.
- **UNIQUE compuesta (dos o más columnas) y FK compuesta:** StarUML no deja modelarlas y el DER no
  lleva notas de texto (decidido el 17/10). No son baches.
- **Esquema común o por aseguradora:** se distingue por el borde de la entidad. Las del esquema
  común (`arbiter_common`) llevan borde azul; las de cada aseguradora, el borde por defecto.
- **FK:** se tilda `Foreign Key` en la columna y se dibuja la relación. `Reference To` queda vacío,
  como en el resto del diagrama.

---

## Para editar en el `.mdj`

El `.mdj` del 24/10 coincide con el esquema, salvo la columna que suma esta rama:

### 40 · `expediente.documentos_pedidos` — columna nueva (23/10)

Los documentos que el analista le pide al asegurado
(`2026-10-23-documentos-pedidos-por-el-analista.sql`).

| Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|
| `documentos_pedidos` (`requested_documents`) | JSONB | no | default `'[]'` |

**Acción:** agregar la columna a `expediente`.

---

## Para el esquema (historias)

Nada: la entrada 39 se cerró el 23/10. `develop` agregó la UNIQUE en `usuario_id` de `asegurado`,
`analista_siniestro` y `referente_aseguradora` (`2026-10-21-un-usuario-por-persona.sql`), y el alta en
bloque ya no cuelga un segundo asegurado del mismo usuario.

---

## En standby

Nada.

---

## Plantilla para la próxima entrada

```
### N · `tabla.columna` — descripción corta del bache

| Columna | Tipo de dato | Nulo | Restricciones |
|---|---|---|---|
| ... | ... | ... | ... |

**Acción:** agregar / dropear / corregir tipo / decisión pendiente (con quién hay que confirmarla).
```
