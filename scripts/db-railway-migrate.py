"""Applies a .sql file (or a -c statement) to the `.env` database with a throwaway Docker psql.

Covers what scripts/db-railway.ps1 does not: applying a single migration, without a local psql.
The connection comes from DB_URL (JDBC format), DB_USER and DB_PASSWORD in `.env`.

    python scripts/db-railway-migrate.py db/migrations/2026-08-30-some-change.sql
    python scripts/db-railway-migrate.py -c "SELECT 1;"
"""
import pathlib
import subprocess
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent


def read_env() -> dict[str, str]:
    env: dict[str, str] = {}
    # utf-8-sig: Notepad may have saved .env with a BOM.
    for line in (REPO_ROOT / ".env").read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        env[key.strip()] = value.strip()
    return env


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)

    env = read_env()
    db_url = env.get("DB_URL", "")
    if not db_url.startswith("jdbc:postgresql://"):
        sys.exit("DB_URL falta o no tiene el formato jdbc:postgresql://... (revisá .env)")
    host_port_db = db_url.removeprefix("jdbc:postgresql://")
    user, password = env.get("DB_USER", ""), env.get("DB_PASSWORD", "")
    if not user or not password:
        sys.exit("DB_USER / DB_PASSWORD faltan en .env")

    print(f"Base: {host_port_db.split('?')[0]}  usuario: {user}", flush=True)

    # Bytes on stdin plus PGCLIENTENCODING: on Windows Python would encode stdin as cp1252 and
    # fail on non-ASCII SQL. Without ON_ERROR_STOP psql continues past errors and exits 0.
    command = ["docker", "run", "--rm", "-i",
               "-e", f"PGPASSWORD={password}", "-e", "PGCLIENTENCODING=UTF8", "postgres:16",
               "psql", f"postgresql://{user}@{host_port_db}", "-v", "ON_ERROR_STOP=1"]
    payload = None
    if sys.argv[1] == "-c":
        command += ["-c", sys.argv[2]]
    else:
        payload = (REPO_ROOT / sys.argv[1]).read_text(encoding="utf-8").encode("utf-8")
        command += ["-f", "-"]

    done = subprocess.run(command, input=payload, capture_output=True)
    print(done.stdout.decode("utf-8", "replace"))
    if done.returncode != 0:
        # Raw stderr may contain the connection string with the password.
        for line in done.stderr.decode("utf-8", "replace").splitlines():
            print("  stderr:", line.replace(password, "<oculto>"), file=sys.stderr)
        sys.exit(f"psql falló (exit {done.returncode}). No se aplicó nada: todo va en una transacción.")
    print("OK", flush=True)


if __name__ == "__main__":
    main()
