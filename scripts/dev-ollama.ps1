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
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ComposeArgs
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

$env:LLM_PROVIDER = 'ollama'
$env:COMPOSE_PROFILES = 'ollama'

Write-Host "Levantando Arbiter con Ollama local..." -ForegroundColor Cyan
docker compose up @ComposeArgs
