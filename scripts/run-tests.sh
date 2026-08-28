#!/usr/bin/env bash
#
# Corre las tres capas de prueba del semáforo de vencimiento (y del módulo cases en general).
#
#   1. Unit backend  — rápido, milisegundos, SIN Docker.
#   2. ITs backend   — Testcontainers (levanta Postgres en Docker). REQUIERE Docker corriendo.
#   3. Front         — specs Jasmine en Chrome headless. REQUIERE Chrome/Chromium.
#
# Uso:  bash scripts/run-tests.sh [--no-it] [--no-front]
#
set -euo pipefail
cd "$(dirname "$0")/.."

RUN_IT=1
RUN_FRONT=1
for arg in "$@"; do
  case "$arg" in
    --no-it)    RUN_IT=0 ;;
    --no-front) RUN_FRONT=0 ;;
    *) echo "opción desconocida: $arg" >&2; exit 2 ;;
  esac
done

echo "══════════ 1/3 · Unit backend (cases-service, sin Docker) ══════════"
mvn -pl cases-service -am test

if [[ "$RUN_IT" == "1" ]]; then
  echo "══════════ 2/3 · ITs backend (Testcontainers — necesita Docker) ══════════"
  # -am: en un checkout limpio common-lib no está instalado en ~/.m2; sin esto el reactor de un
  # solo módulo no puede resolver la dependencia.
  mvn -pl cases-service -am verify -Pit
else
  echo "── (saltando ITs: --no-it) ──"
fi

if [[ "$RUN_FRONT" == "1" ]]; then
  echo "══════════ 3/3 · Front (Karma headless) ══════════"
  # Karma necesita saber dónde está Chrome si no está en el PATH esperado.
  if [[ -z "${CHROME_BIN:-}" ]]; then
    for c in /usr/bin/google-chrome /usr/bin/google-chrome-stable /usr/bin/chromium; do
      [[ -x "$c" ]] && export CHROME_BIN="$c" && break
    done
  fi
  ( cd arbiter-frontend && npm test -- --watch=false --browsers=ChromeHeadless )
else
  echo "── (saltando front: --no-front) ──"
fi

echo "✔ Todo verde. Cobertura backend: cases-service/target/site/jacoco/index.html"
