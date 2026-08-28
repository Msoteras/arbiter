#!/usr/bin/env python3
"""Comprueba que el frontend rutee igual en desarrollo y en el despliegue.

El frontend usa rutas relativas (`apiBaseUrl: '/api/v1'`), así que alguien tiene que traducir
cada prefijo al módulo que lo atiende. Eso está escrito DOS veces:

  * arbiter-frontend/proxy.conf.json      -> lo usa `ng serve`
  * arbiter-frontend/nginx.conf.template  -> lo usa la imagen desplegada

Una ruta agregada solo en la primera funciona durante todo el desarrollo y da 404 apenas se
despliega. Este script existe para que esa divergencia falle en el CI y no en la demo.
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROXY = ROOT / "arbiter-frontend" / "proxy.conf.json"
TEMPLATE = ROOT / "arbiter-frontend" / "nginx.conf.template"

# El puerto de cada módulo en desarrollo y el nombre de su variable en el template son dos formas
# de nombrar lo mismo; esto las lleva a un vocabulario común para poder compararlas.
PORT_TO_SERVICE = {
    "8080": "auth",
    "8081": "rules",
    "8082": "classification",
    "8083": "cases",
}
VAR_TO_SERVICE = {
    "AUTH": "auth",
    "RULES": "rules",
    "CLASSIFICATION": "classification",
    "CASES": "cases",
}


def dev_routes() -> dict[str, str]:
    raw = json.loads(PROXY.read_text(encoding="utf-8"))
    routes = {}
    for path, cfg in raw.items():
        port = re.search(r":(\d+)", cfg["target"])
        if not port or port.group(1) not in PORT_TO_SERVICE:
            sys.exit(f"proxy.conf.json: no puedo mapear el target de {path!r}: {cfg['target']!r}")
        routes[path] = PORT_TO_SERVICE[port.group(1)]
    return routes


def prod_routes() -> dict[str, str]:
    text = TEMPLATE.read_text(encoding="utf-8")
    pattern = re.compile(
        r"location\s+(?P<path>/api/\S+)\s*\{"
        r"[^}]*?set\s+\$upstream\s+\$\{(?P<var>[A-Z_]+)_SERVICE_URL\}",
        re.DOTALL,
    )
    routes = {}
    for m in pattern.finditer(text):
        var = m.group("var")
        if var not in VAR_TO_SERVICE:
            sys.exit(f"nginx.conf.template: variable inesperada ${{{var}_SERVICE_URL}}")
        routes[m.group("path")] = VAR_TO_SERVICE[var]
    return routes


def main() -> int:
    dev, prod = dev_routes(), prod_routes()
    if not prod:
        sys.exit("nginx.conf.template: no se reconoció ninguna ruta — ¿cambió la forma del archivo?")

    problems = []
    for path in sorted(set(dev) | set(prod)):
        d, p = dev.get(path), prod.get(path)
        if d == p:
            continue
        if p is None:
            problems.append(f"  {path}: está en proxy.conf.json ({d}) y falta en nginx.conf.template")
        elif d is None:
            problems.append(f"  {path}: está en nginx.conf.template ({p}) y falta en proxy.conf.json")
        else:
            problems.append(f"  {path}: dev apunta a {d} y prod a {p}")

    if problems:
        print("Las dos tablas de ruteo del frontend divergen:\n")
        print("\n".join(problems))
        print(
            "\nArreglá arbiter-frontend/proxy.conf.json y/o arbiter-frontend/nginx.conf.template"
            "\npara que declaren las mismas rutas hacia los mismos módulos."
        )
        return 1

    print(f"Ruteo consistente: {len(dev)} rutas iguales en desarrollo y despliegue.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
