<#
.SYNOPSIS
    Levanta Arbiter contra la BD de Railway, con Ollama local como motor de
    clasificación.

.DESCRIPTION
    Usa docker-compose.railway.yml, así que NO levanta Postgres local: los
    módulos apuntan a la base compartida de Railway (DB_URL/DB_USER/DB_PASSWORD
    del .env de la raíz). Ollama sí corre acá al lado, con su perfil de compose
    activado. No toca tu .env.

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

# docker-compose.override.yml queda deliberadamente afuera: es el override del stack LOCAL
# (docker-compose.yml), y lo que trae —redirigir DB_URL a Railway— ya lo hace este compose de
# entrada. docker-compose.ollama.yml va último a propósito: un override que fije
# `LLM_PROVIDER: gemini` literal no lo pisa ninguna variable de entorno, y sin este archivo
# el script podría clasificar por Gemini en silencio.
$composeFiles = @('-f', 'docker-compose.railway.yml', '-f', 'docker-compose.ollama.yml')

Write-Host "Levantando Arbiter con Ollama local, contra la BD de Railway..." -ForegroundColor Cyan
docker compose @composeFiles up @args
