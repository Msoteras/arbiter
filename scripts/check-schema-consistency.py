#!/usr/bin/env python3
"""Compares the tables and columns db/init-multitenant.sql defines against a live database.

The schema is init-multitenant.sql plus hand-applied patches in db/migrations/, with no record
of which ones ran, so the only reliable check is an actual comparison.

Usage:
    python scripts/check-schema-consistency.py

Reads DB_URL / DB_USER / DB_PASSWORD from .env, needs Docker for a throwaway psql client, and
exits 1 on any difference. It does NOT check column types, constraints, indexes or data, nor
non-additive migrations: verify those by hand.
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

# Regions of db/init-multitenant.sql are located by the header of the function that creates
# them, not by line number, which goes stale silently as the file grows.
TENANT_FN_HEADER = "CREATE OR REPLACE FUNCTION arbiter_common.create_tenant_schema("    # arbiter_bbva/provincia
INSURER_FN_HEADER = "CREATE OR REPLACE FUNCTION arbiter_common.create_insurer_db_schema("  # aseguradora_bbva/provincia
FN_TERMINATOR = "$fn$ LANGUAGE plpgsql;"

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


def region_bounds(lines: list[str]) -> tuple[tuple[int, int], tuple[int, int], tuple[int, int]]:
    """1-indexed, inclusive (start, end) of the common, tenant and insurer regions."""
    def find(needle: str, after: int = 0) -> int:
        for idx in range(after, len(lines)):
            if lines[idx].strip().startswith(needle):
                return idx + 1
        sys.exit(f"No encuentro '{needle}' en {SQL_PATH.relative_to(REPO_ROOT)} "
                 "(¿cambió el encabezado de la función?).")

    tenant_start = find(TENANT_FN_HEADER)
    insurer_start = find(INSURER_FN_HEADER)
    tenant_range = (tenant_start, find(FN_TERMINATOR, tenant_start))
    insurer_range = (insurer_start, find(FN_TERMINATOR, insurer_start))
    # Everything before the first function is the common schema.
    return (1, min(tenant_start, insurer_start) - 1), tenant_range, insurer_range


def parse_region(lines: list[str], start: int, end: int,
                  table_pattern: re.Pattern) -> dict[str, list[str]]:
    """Maps table -> columns for each CREATE TABLE in [start, end).

    Scans character by character: the closing line of a %I block also closes the wrapping
    format() call, so a line-based cut would overshoot the end of the column list.
    Comments are not stripped here, so parentheses in them must stay balanced.
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
                        continue  # skip the CREATE TABLE's own opening "("
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
    common_range, tenant_range, insurer_range = region_bounds(lines)
    common = parse_region(lines, *common_range, re.compile(r"CREATE TABLE arbiter_common\.(\w+)"))
    tenant = parse_region(lines, *tenant_range, re.compile(r"CREATE TABLE %I\.(\w+)"))
    insurer = parse_region(lines, *insurer_range, re.compile(r"CREATE TABLE %I\.(\w+)"))

    # An empty region means the parser read the wrong lines, not that the schema has no tables:
    # diffing it anyway turns a parser bug into a list of made-up differences.
    for label, tables in (("comunes", common), ("por tenant", tenant), ("de aseguradora", insurer)):
        if not tables:
            sys.exit(f"No se parseó ninguna tabla {label} en {SQL_PATH.relative_to(REPO_ROOT)}: "
                     "el parser está leyendo mal el archivo, el diff no sería confiable.")

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
        # Never print raw stderr: it may contain the connection string with the password.
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
