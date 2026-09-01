#!/bin/sh
# Materialises the Vertex service-account key before handing control to the JVM.
#
# The google-genai SDK authenticates through ADC, and ADC only reads credentials from a FILE
# pointed at by GOOGLE_APPLICATION_CREDENTIALS — it cannot take the JSON from an environment
# variable. Docker Compose solves that by bind-mounting the file (see
# docker-compose.railway.vertex.yml); Railway has no equivalent, so the key travels as a variable
# and this script writes it out at startup.
#
# Base64 and not raw JSON on purpose: a service-account key is multi-line, and Railway's Raw
# Editor parses KEY=value one line at a time, so pasting the JSON directly truncates it at the
# first newline. Base64 is a single line and survives every input path.
#
# Doing nothing when the variable is absent is what keeps Compose working unchanged: there,
# GOOGLE_APPLICATION_CREDENTIALS is already set to the mounted path and must not be overwritten.

set -e

if [ -n "${GOOGLE_APPLICATION_CREDENTIALS_B64}" ]; then
    CREDENTIALS_FILE=/tmp/gcp-adc.json

    # umask before the write, not chmod after: chmod would leave a window where the key is
    # world-readable, short but real.
    (umask 077 && echo "${GOOGLE_APPLICATION_CREDENTIALS_B64}" | base64 -d > "${CREDENTIALS_FILE}")

    # A truncated or mis-pasted value decodes into garbage, and the SDK's failure surfaces much
    # later as an opaque auth error during the first classification. Failing here instead points
    # at the actual cause.
    if [ ! -s "${CREDENTIALS_FILE}" ]; then
        echo "[entrypoint] GOOGLE_APPLICATION_CREDENTIALS_B64 decoded to an empty file — check the variable." >&2
        exit 1
    fi

    export GOOGLE_APPLICATION_CREDENTIALS="${CREDENTIALS_FILE}"
    echo "[entrypoint] Vertex credentials written to ${CREDENTIALS_FILE}"
fi

# exec so the JVM becomes PID 1 and receives Railway's SIGTERM on redeploy, instead of the shell
# swallowing it and letting the container be killed after the grace period.
exec java -Duser.timezone=America/Argentina/Buenos_Aires -jar app.jar
