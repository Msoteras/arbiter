#!/bin/sh
# ADC only reads credentials from a file, so on platforms without bind mounts the Vertex key
# arrives base64-encoded (a multi-line JSON gets truncated by line-based env editors) and is
# written out here. When the variable is absent, GOOGLE_APPLICATION_CREDENTIALS is left untouched.

set -e

if [ -n "${GOOGLE_APPLICATION_CREDENTIALS_B64}" ]; then
    CREDENTIALS_FILE=/tmp/gcp-adc.json

    # umask before writing, not chmod after, so the key is never world-readable.
    (umask 077 && echo "${GOOGLE_APPLICATION_CREDENTIALS_B64}" | base64 -d > "${CREDENTIALS_FILE}")

    # Fail fast: otherwise a bad value surfaces later as an opaque auth error.
    if [ ! -s "${CREDENTIALS_FILE}" ]; then
        echo "[entrypoint] GOOGLE_APPLICATION_CREDENTIALS_B64 decoded to an empty file — check the variable." >&2
        exit 1
    fi

    export GOOGLE_APPLICATION_CREDENTIALS="${CREDENTIALS_FILE}"
    echo "[entrypoint] Vertex credentials written to ${CREDENTIALS_FILE}"
fi

# exec so the JVM is PID 1 and receives SIGTERM on redeploy.
exec java -Duser.timezone=America/Argentina/Buenos_Aires -jar app.jar
