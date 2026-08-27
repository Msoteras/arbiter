<#
.SYNOPSIS
    Levanta Arbiter completo con Ollama local como motor de clasificación.

.DESCRIPTION
    Fija LLM_PROVIDER=ollama y prende el profile "ollama" (así arrancan los
    contenedores ollama/ollama-init) para este proceso, sin tocar tu .env.
    El resto de las variables (DB, JWT, Auth0, SendGrid...) se siguen leyendo
    del .env de la raíz, como siempre.

    Cualquier argumento extra se pasa tal cual a `docker compose up`.

.EXAMPLE
    .\scripts\dev-ollama.ps1

.EXAMPLE
    .\scripts\dev-ollama.ps1 --build -d
#>
# Los flags extra se leen del $args automático a propósito, sin declarar un parámetro con
# ValueFromRemainingArguments: eso convierte al script en advanced function y le suma los
# common parameters, y ahí PowerShell se queda con `-d` porque es prefijo único de `-Debug`.
# El flag nunca llegaba a Compose y el stack arrancaba en foreground creyendo estar detached.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

$env:LLM_PROVIDER = 'ollama'
$env:COMPOSE_PROFILES = 'ollama'

# Al pasar -f explícito, Compose deja de autoincluir docker-compose.override.yml — hay que
# sumarlo a mano si existe, o quien lo use para apuntar a Railway termina levantando contra
# el Postgres local. docker-compose.ollama.yml va último a propósito: un override que fije
# `LLM_PROVIDER: gemini` literal no lo pisa ninguna variable de entorno, y sin este archivo
# el script clasificaría por Gemini en silencio.
$composeFiles = @('-f', 'docker-compose.yml')
if (Test-Path 'docker-compose.override.yml') {
    $composeFiles += @('-f', 'docker-compose.override.yml')
}
$composeFiles += @('-f', 'docker-compose.ollama.yml')

Write-Host "Levantando Arbiter con Ollama local..." -ForegroundColor Cyan
docker compose @composeFiles up @args
