#!/usr/bin/env python3
"""Compara lo que db/init-multitenant.sql define contra el esquema real de una base viva.

Por qué existe: no hay Flyway/Liquibase — el esquema vive en db/init-multitenant.sql (solo
CREATE, pensado para una base vacía) más una serie de parches manuales sueltos
(db/migrate-*.sql, db/migrations/*.sql) sin tabla de control de cuáles ya se corrieron. La única
forma confiable de saber si una base viva (Railway hoy, Supabase después de la migración) quedó
consistente con lo que el repo dice que debería tener es comparar de verdad, no de memoria.

Qué compara: tablas y columnas, por (schema, tabla), en las cinco bases que el script define
(arbiter_common, arbiter_bbva, arbiter_provincia, aseguradora_bbva, aseguradora_provincia).
NO compara tipos de columna, constraints, índices ni datos — ver "Lo que esto NO prueba" abajo.

Uso:
    python scripts/check-schema-consistency.py

Lee DB_URL / DB_USER / DB_PASSWORD de .env (mismas variables que usan los servicios). Necesita
Docker (arranca un cliente psql descartable, ver db/migrate-*.sql para el mismo patrón) y sale
con status 1 si encuentra alguna diferencia — pensado para poder engancharlo a un chequeo manual
antes/después de migrar a Supabase, no para correr en cada build.

Lo que esto NO prueba (verificar aparte si hace falta):
  - Tipos de columna, NOT NULL, defaults, FKs, índices, CHECKs — solo nombres.
  - Migraciones no aditivas (un ALTER que borra filas, un índice UNIQUE que puede fallar en
    silencio si hay duplicados). Esas se verifican una por una, a mano — ver
    db/migrations/*.sql y db/migrate-*.sql para la lista de lo que hay que confirmar así.
  - Los datos de seed-demo.sql en sí — esperable que difieran entre ambientes por las pruebas
    de cada uno; este script mira estructura, no filas.
"""
from __future__ import annotations

import csv
import io
import os
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SQL_PATH = REPO_ROOT / "db" / "init-multitenant.sql"
ENV_PATH = REPO_ROOT / ".env"

SCHEMAS = ("arbiter_common", "arbiter_bbva", "arbiter_provincia",
           "aseguradora_bbva", "aseguradora_provincia")

# Rangos de línea (1-indexed) de cada región de db/init-multitenant.sql. Se ubican a mano porque
# son estables (el script casi no cambia de forma) y evita depender de un parser de SQL real.
# Si el diff de abajo empieza a fallar con montones de tablas de golpe, lo primero a revisar es
# si estos rangos corrieron de línea.
COMMON_RANGE = (1, 275)
TENANT_FN_RANGE = (276, 1120)      # create_tenant_schema -> arbiter_bbva, arbiter_provincia
INSURER_FN_RANGE = (1133, 1241)    # create_insurer_db_schema -> aseguradora_bbva/provincia

NON_COLUMN_PREFIXES = ("CONSTRAINT", "PRIMARY KEY", "FOREIGN KEY", "UNIQUE", "CHECK", "EXCLUDE")


def load_env() -> dict[str, str]:
    env = dict(os.environ)
    if ENV_PATH.exists():
        for line in ENV_PATH.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            env.setdefault(key.strip(), value.strip())
    return env


def strip_sql_comment(line: str) -> str:
    idx = line.find("--")
    return line[:idx] if idx != -1 else line


def extract_columns(block_text_lines: list[str]) -> list[str]:
    text = "\n".join(strip_sql_comment(l) for l in block_text_lines)
    items, depth, current = [], 0, []
    for ch in text:
        if ch == "(":
            depth += 1
            current.append(ch)
        elif ch == ")":
            depth -= 1
            current.append(ch)
        elif ch == "," and depth == 0:
            items.append("".join(current))
            current = []
        else:
            current.append(ch)
    items.append("".join(current))

    columns = []
    for item in items:
        stripped = item.strip()
        if not stripped:
            continue
        if any(stripped.upper().startswith(p) for p in NON_COLUMN_PREFIXES):
            continue
        columns.append(stripped.split()[0].strip('"'))
    return columns


def parse_region(lines: list[str], start: int, end: int,
                  table_pattern: re.Pattern) -> dict[str, list[str]]:
    """Extrae tabla -> columnas de un CREATE TABLE(...) por vez, dentro de [start, end).

    Corta carácter por carácter y no por línea: la línea de cierre de un bloque %I trae DOS
    paréntesis de cierre (el del CREATE TABLE y el del format() que lo envuelve, tipo
    ")$ddl$, p_schema);") — cortar por línea entera se pasa de largo y nunca ve el depth==0
    exacto que separa "esto es una columna" de "esto es ruido de la función".
    """
    tables: dict[str, list[str]] = {}
    i = start - 1
    while i < end:
        m = table_pattern.search(lines[i])
        if not m:
            i += 1
            continue
        table = m.group(1)
        depth, started, done = 0, False, False
        collected: list[str] = []
        j = i
        while j < end and not done:
            for ch in lines[j]:
                if ch == "(":
                    depth += 1
                    if depth == 1:
                        started = True
                        continue  # no incluir el "(" de apertura del propio CREATE TABLE
                elif ch == ")":
                    if started and depth == 1:
                        done = True
                        break
                    depth -= 1
                if started:
                    collected.append(ch)
            if not done:
                collected.append("\n")
            j += 1
        if not done:
            print(f"  [aviso parser] {table}: bloque desde línea {i + 1} nunca cerró, salteada",
                  file=sys.stderr)
        else:
            tables[table] = extract_columns("".join(collected).splitlines())
        i = j
    return tables


def expected_schema() -> dict[tuple[str, str], set[str]]:
    lines = SQL_PATH.read_text(encoding="utf-8").splitlines()
    common = parse_region(lines, *COMMON_RANGE, re.compile(r"CREATE TABLE arbiter_common\.(\w+)"))
    tenant = parse_region(lines, *TENANT_FN_RANGE, re.compile(r"CREATE TABLE %I\.(\w+)"))
    insurer = parse_region(lines, *INSURER_FN_RANGE, re.compile(r"CREATE TABLE %I\.(\w+)"))

    expected: dict[tuple[str, str], set[str]] = {}
    for table, cols in common.items():
        expected[("arbiter_common", table)] = set(cols)
    for schema in ("arbiter_bbva", "arbiter_provincia"):
        for table, cols in tenant.items():
            expected[(schema, table)] = set(cols)
    for schema in ("aseguradora_bbva", "aseguradora_provincia"):
        for table, cols in insurer.items():
            expected[(schema, table)] = set(cols)

    print(f"Definidas en {SQL_PATH.relative_to(REPO_ROOT)}: {len(common)} comunes, "
          f"{len(tenant)} por tenant x2, {len(insurer)} de aseguradora x2 "
          f"= {len(expected)} (schema, tabla) esperadas.\n")
    return expected


def fetch_live_schema(env: dict[str, str]) -> dict[tuple[str, str], set[str]]:
    db_url = env.get("DB_URL", "")
    if not db_url.startswith("jdbc:postgresql://"):
        sys.exit("DB_URL falta o no tiene el formato jdbc:postgresql://... (revisá .env)")
    host_port_db = db_url.removeprefix("jdbc:postgresql://")
    db_user = env.get("DB_USER", "")
    db_password = env.get("DB_PASSWORD", "")
    if not db_user or not db_password:
        sys.exit("DB_USER / DB_PASSWORD faltan en .env")

    query = f"""
        SELECT table_schema, table_name, column_name
          FROM information_schema.columns
         WHERE table_schema IN ({",".join(f"'{s}'" for s in SCHEMAS)})
         ORDER BY table_schema, table_name, column_name;
    """
    proc = subprocess.run(
        ["docker", "run", "--rm", "-e", f"PGPASSWORD={db_password}", "postgres:16",
         "psql", f"postgresql://{db_user}@{host_port_db}", "-t", "-A", "-F,", "-c", query],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        # Nunca imprimir stderr crudo acá: puede traer la connection string con la password si
        # psql falla antes de conectar (host mal escrito, etc).
        sys.exit("No se pudo consultar la base — revisá DB_URL/DB_USER/DB_PASSWORD y que Docker "
                 "esté corriendo.")

    live: dict[tuple[str, str], set[str]] = defaultdict(set)
    for row in csv.reader(io.StringIO(proc.stdout)):
        if len(row) != 3:
            continue
        schema, table, column = row
        live[(schema, table)].add(column)
    return live


def main() -> int:
    env = load_env()
    expected = expected_schema()
    live = fetch_live_schema(env)

    expected_tables, live_tables = set(expected), set(live)

    missing_tables = sorted(expected_tables - live_tables)
    extra_tables = sorted(live_tables - expected_tables)

    print(f"{'=' * 70}\nTablas esperadas que NO están en la base ({len(missing_tables)})\n{'=' * 70}")
    for s, t in missing_tables:
        print(f"  FALTA  {s}.{t}")

    print(f"\n{'=' * 70}\nTablas en la base que este script no define ({len(extra_tables)})\n{'=' * 70}")
    for s, t in extra_tables:
        print(f"  EXTRA  {s}.{t}")

    print(f"\n{'=' * 70}\nDiferencias de columnas, tabla por tabla\n{'=' * 70}")
    any_col_diff = False
    for key in sorted(expected_tables & live_tables):
        missing_cols = sorted(expected[key] - live[key])
        extra_cols = sorted(live[key] - expected[key])
        if missing_cols or extra_cols:
            any_col_diff = True
            print(f"  {key[0]}.{key[1]}")
            for c in missing_cols:
                print(f"      falta en la base:  {c}")
            for c in extra_cols:
                print(f"      la base tiene de más: {c}")
    if not any_col_diff:
        print("  Ninguna — todas las tablas presentes en ambos lados tienen exactamente las "
              "mismas columnas.")

    ok = not missing_tables and not extra_tables and not any_col_diff
    print("\nRESULTADO:", "consistente" if ok else "hay diferencias — ver arriba")
    if not ok:
        print("\nRecordá lo que este script NO ve: tipos/constraints/índices, y las migraciones "
              "no aditivas (borrados, índices UNIQUE) — esas se confirman a mano, una por una.")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
