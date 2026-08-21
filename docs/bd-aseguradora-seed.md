# BD Aseguradora — lectura real desde el seeder (perfil `insurer-db`)

Hasta ahora `classification-service` resolvía **póliza, coberturas e historial** del asegurado
con datos **hardcodeados en memoria** (`MockInsurerAdapter`). Ahora puede leerlos de verdad
desde la **BD Aseguradora** (el schema `aseguradora`, que simula la base de datos de la
compañía), cargada por el seeder `db/datos-aseguradoras.sql`.

Esto se activa con el **perfil de Spring `insurer-db`**. Sin ese perfil, todo sigue igual que
antes (mock en memoria) — no rompe nada.

---

## Qué cambió

| Componente | Rol |
|------------|-----|
| `db/datos-aseguradoras.sql` | Seeder que crea el schema `aseguradora` (compañías, asegurados, pólizas, coberturas, historial de siniestros) en la **misma** base `arbiter`. |
| `InsurerDatabaseAdapter` (nuevo) | Implementación de `InsurerAdapter` que lee ese schema por JDBC. `@Profile("insurer-db")` + `@Primary`: solo se activa —y gana sobre el mock— cuando el perfil está prendido. |
| `MockInsurerAdapter` (sin cambios) | Sigue siendo el default cuando el perfil `insurer-db` **no** está activo (tests, dev sin BD). |
| `docker-compose.yml` | `classification-service` ahora arranca con `SPRING_PROFILES_ACTIVE=insurer-db` por defecto. |

**Importante:** el adapter solo se consulta cuando **corre una clasificación nueva**
(`POST /api/v1/claims` o `/api/v1/classifications`). Las pantallas del front que listan
expedientes leen las tablas del sistema (`cases`, `classification_log`), que ya traen los
datos desnormalizados — esas no cambian por el seeder.

---

## Cómo levantarlo (paso a paso)

Desde la raíz del repo, con Docker corriendo:

```bash
# 1. Levantar el stack (postgres, ollama, y los servicios Java)
docker compose up -d

# 2. Cargar los DOS seeders en la misma base 'arbiter'
#    - init.sql              → tablas del sistema (users, cases, classification_log...)
#    - datos-aseguradoras.sql → schema 'aseguradora' (la BD de la compañía)
docker exec -i arbiter-postgres-1 psql -U arbiter -d arbiter < db/init.sql
docker exec -i arbiter-postgres-1 psql -U arbiter -d arbiter < db/datos-aseguradoras.sql

# 3. Si cambiaste código del servicio, rebuildeá su imagen
docker compose build classification-service
docker compose up -d --no-deps --force-recreate classification-service
```

El servicio arranca con el perfil ya prendido. Verificás en los logs:

```bash
docker logs arbiter-classification-service-1 | grep "profile is active"
# → The following 1 profile is active: "insurer-db"
```

### Correrlo en local (sin Docker)

```bash
mvn -pl classification-service -am spring-boot:run -Dspring-boot.run.profiles=insurer-db
# (necesitás postgres con ambos seeders cargados)
```

### Volver al mock (apagar la BD aseguradora)

Correr el servicio **sin** el perfil. En compose:

```bash
CLASSIFICATION_PROFILES= docker compose up -d classification-service
```

---

## Cómo verificar que lee de la BD (no del mock)

Dispará una clasificación y mirá el log del orchestrator. Con el seeder, la póliza
`POL-CEL-2025-099` resuelve a **Julián Pérez** (el mock decía *Marcelo Gómez*):

```bash
# token de analista
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"analista@arbiter.test","password":"changeme123"}' \
  | sed -E 's/.*"token":"([^"]+)".*/\1/')

# el 'claim' va como part JSON — usá un archivo para evitar problemas de encoding con curl
curl -s -X POST http://localhost:8082/api/v1/classifications \
  -H "Authorization: Bearer $TOKEN" \
  -F "claim=@claim.json;type=application/json"

docker logs arbiter-classification-service-1 | grep "Policy OK"
# → Policy OK — insured='Julián Pérez' upToDate=true insuredAmount=1200000.00
```

---

## Cómo mapea el seeder al DTO (`InsuredPolicy` / `InsuredHistory`)

El DTO es más plano que el schema, así que el adapter deriva algunos campos:

| Campo del DTO | De dónde sale |
|---------------|---------------|
| `insuredName` / `insuredId` | `asegurado.nombre + apellido` / `documento` (join por `poliza.titular_id`) |
| `branch` / `product` / vigencias | `poliza.rama` / `producto` / `vigencia_desde`·`hasta` |
| `upToDate` | `estado_pago = 'AL_DIA'` **y** `saldo_deuda = 0` |
| `insuredAmount` / `deductible` (nivel póliza) | **cobertura primaria** (menor `orden`); la franquicia % se convierte a monto absoluto |
| `coverages[]` | todas las filas de `cobertura` de la póliza |
| `applicableClauses` | vacío por ahora (el seeder todavía no tiene catálogo de cláusulas) |
| `previousClaimsCount` / `claims[]` | filas de `siniestro_historico` del asegurado (por `documento`) |
| `totalAmountClaimed` | suma de `monto_indemnizado` |
| `customerSince` | `MIN(vigencia_desde)` de las pólizas del asegurado |

---

## Notas / cosas a saber

- **Los nombres cambian respecto del mock.** Ej.: `POL-CEL-2025-099` era *Marcelo Gómez* en el
  mock y ahora es *Julián Pérez* (seeder). Es esperable.
- **Correr solo el módulo:** usá siempre `-am` (`mvn -pl classification-service -am ...`) o
  instalá `common-lib` antes; hay tests que dependen de código reciente de `common-lib`.
- **El seeder no se auto-carga** al levantar el compose (el volumen de postgres persiste). Hay
  que correr los `psql` a mano la primera vez o cuando quieras resetear datos.
- **Los tests no se ven afectados:** sin el perfil, el bean activo sigue siendo el mock, así que
  la suite (58 tests) pasa igual.
- **Martina tiene un mail de prueba en `asegurado.email` (18/8).** En los dos esquemas
  (`aseguradora_bbva` y `aseguradora_provincia`) el dni 42.987.654 figura con
  `martina.soteras@example.com`, mientras que Julián (30.555.777) ya tiene uno real.
  Se ve en el wizard de nueva denuncia, en "Datos de contacto", y **no se puede editar**: el campo
  es `[readonly]` cuando la póliza trae mail, porque muestra lo que la aseguradora tiene
  registrado — es correcto que sea así, el dato viejo es el del seed.
  **No afecta las notificaciones**, que salen de `arbiter_*.insured.email` (otra tabla, otro
  esquema). Si molesta para una demo, es un UPDATE de una fila por esquema.
