# Migración a `expediente` / `clasificacion_expediente` — qué falta y por qué no fue parte de DER-mapeo

Handoff de Fede para el equipo (y en especial para quien arme el PR de `feature/mapeo-der`).
Documenta por qué estas 2 entidades del DER quedaron afuera del mapeo, qué hace falta para
construirlas de verdad, y cómo se cruza con el trabajo de multi-tenant que arrancó en paralelo.

**Estado:** nada de esto está hecho. Es la única pieza que falta para que `feature/mapeo-der`
diga "100% del DER contemplado en el código" — todo lo demás ya está.

---

## Por qué no son un gap de mapeo más

El resto de las entidades del DER que se fueron construyendo en `feature/mapeo-der` eran
"cáscara": tabla + entidad + seed, sin nada más apuntándoles todavía (`Metric`, `Coverage`,
`Policy`, `DocumentRequirement`, etc.). Agregarlas no genera ambigüedad porque no compiten con
nada que ya exista.

`expediente` y `clasificacion_expediente` son distintas: **ya existen** como `cases`
(cases-service) y `classification_log` (classification-service), con datos reales en uso en
toda la app — wizard de alta, bandeja, gauge de fraude, decisión del analista, frontend entero.
Crearlas como cáscara al lado de `cases`/`classification_log` generaría **dos tablas
representando lo mismo**, con ambigüedad real sobre cuál es la fuente de verdad. No es agregar
una entidad, es reemplazar una que ya está viva.

## Qué hace falta para que sean el reemplazo real (no cáscara)

Dos frentes, y **el segundo bloquea al primero**:

### 1. Insured, InsurerAgreement, Coverage, Policy y PolicyQuery necesitan datos reales, no cáscara

`expediente.poliza_id`/`cobertura_id` (y lo que dependa de `Insured`/`InsurerAgreement`) necesitan
algo real del otro lado. Hoy esas 5 entidades (`Insured.java`, `InsurerAgreement.java` en
auth-service; `Coverage.java`, `Policy.java`, `PolicyQuery.java` en cases-service) están
construidas pero vacías — sin el sync/cron que trae los datos reales de la BD Aseguradora
(decisión #10 de `CLAUDE.md`: Arbiter persiste snapshots locales, no consulta en vivo), un FK a
estas tablas apunta a la nada. `PolicyController`/`PolicyService` hoy siguen leyendo en vivo de
`InsurerAdapter`, no de estas tablas.

### 2. Migrar cases-service y classification-service enteros

Controllers, services, DTOs, y el frontend que hoy leen/escriben `cases`/`classification_log`
tienen que pasar a usar `expediente`/`clasificacion_expediente`. Esto no es un rename de tabla:
`cases` hoy tiene campos de texto libre (`branch`, `product`, `claim_cause`, `insured_item`,
`policy_number`, `insured_name`) que en `expediente` son FKs a catálogos reales.

## Se cruza con el handoff multi-tenant — no encararlo dos veces

El otro trabajo en curso (rama `feature/multitenant-auth`, ver `db/README-multitenant.md` y
`db/init-multitenant.sql`) **también** cambia `cases`/`classification_log` de raíz en el
esquema real de Aylén:

- `classification_log` se parte en `llm_analysis` + `llm_reason` + `case_classification`.
- `cases` pasa a vivir en el esquema del tenant, con las mismas FKs en vez de texto libre que
  ya menciona el punto 2 de arriba.

Osea que el DER dibuja el mismo destino final que el esquema multi-tenant ya construyó. Migrar
`cases`/`classification_log` → `expediente`/`clasificacion_expediente` **antes** de que
termine la migración a multi-tenant sería trabajo por duplicado — se haría una vez contra el
esquema viejo y habría que rehacerlo contra el nuevo. Recomendación: **esperar a que
`feature/multitenant-auth` avance más** (al menos que `cases-service` esté migrado ahí) antes de
arrancar esto.

## Resumen para decidir

| Pregunta | Respuesta |
|---|---|
| ¿Bloquea cerrar "DER - 2da Parte"? | No — el resto del DER ya está, esto es la única pieza afuera, documentada como historia aparte. |
| ¿Se puede arrancar ya? | Técnicamente sí, pero duplicaría trabajo si `feature/multitenant-auth` sigue avanzando en paralelo. |
| ¿Quién decide cuándo? | El equipo — necesita, como mínimo, decidir el sync/cron de Bucket E (todavía no existe ni como diseño) y la secuencia con el multi-tenant. |
