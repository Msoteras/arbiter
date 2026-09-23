<#
.SYNOPSIS
    Starts Arbiter against the Railway database, with local Ollama as the
    classification model.

.DESCRIPTION
    Uses docker-compose.railway.yml: no local Postgres. The modules connect with
    DB_URL/DB_USER/DB_PASSWORD from the root .env, which is not modified. Ollama
    runs locally under its compose profile.

    Extra arguments are passed as-is to `docker compose up`.

.EXAMPLE
    .\scripts\dev-ollama.ps1

.EXAMPLE
    .\scripts\dev-ollama.ps1 --build -d
#>
# Extra flags come from the automatic $args on purpose: a ValueFromRemainingArguments
# parameter makes this an advanced function, and PowerShell would then take `-d` as `-Debug`.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

$env:LLM_PROVIDER = 'ollama'
$env:COMPOSE_PROFILES = 'ollama'

# docker-compose.override.yml is left out on purpose: it belongs to the local stack.
# docker-compose.ollama.yml goes last so it beats any override that hardcodes
# `LLM_PROVIDER: gemini`, which no environment variable can override.
$composeFiles = @('-f', 'docker-compose.railway.yml', '-f', 'docker-compose.ollama.yml')

Write-Host "Levantando Arbiter con Ollama local, contra la BD de Railway..." -ForegroundColor Cyan
docker compose @composeFiles up @args
