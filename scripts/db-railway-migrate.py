"""Aplica un archivo .sql contra la base de `.env`, con un cliente psql descartable por Docker.

Existe porque `scripts/db-railway.ps1` sólo sabe hacer check/reset/init/seed/verify —no aplicar una
migración suelta— y además necesita un `psql` instalado, que en Windows no suele estar. Acá el
cliente lo pone Docker, igual que `check-schema-consistency.py`.

La conexión sale de `.env` (DB_URL en formato JDBC, DB_USER, DB_PASSWORD): la credencial vive en un
solo lugar gitigonoreado y no queda en el historial de la terminal ni en la línea de comandos.

    python scripts/db-railway-migrate.py db/migrations/2026-08-30-lo-que-sea.sql
    python scripts/db-railway-migrate.py -c "SELECT 1;"
"""
import pathlib
import subprocess
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent


def read_env() -> dict[str, str]:
    env: dict[str, str] = {}
    # utf-8-sig: el .env puede venir con BOM si alguien lo editó con Notepad.
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

    # PGCLIENTENCODING + stdin en bytes: los .sql del repo tienen acentos y flechas, y en Windows
    # Python encodearía stdin en cp1252 y reventaría antes de llegar a psql.
    # ON_ERROR_STOP: sin esto psql sigue tras un error y sale con 0, que es cómo se llega a una
    # migración a medio aplicar creyendo que salió bien.
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
        # El stderr crudo puede traer la connection string con la password si psql falla antes de
        # conectar (host mal escrito, etc.), así que se filtra antes de imprimirlo.
        for line in done.stderr.decode("utf-8", "replace").splitlines():
            print("  stderr:", line.replace(password, "<oculto>"), file=sys.stderr)
        sys.exit(f"psql falló (exit {done.returncode}). No se aplicó nada: todo va en una transacción.")
    print("OK", flush=True)


if __name__ == "__main__":
    main()
