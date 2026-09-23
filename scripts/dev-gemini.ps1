<#
.SYNOPSIS
    Starts Arbiter against the Railway database, with Gemini (Vertex AI) as the
    classification model.

.DESCRIPTION
    Uses docker-compose.railway.yml: no local Postgres and no Ollama. The modules
    connect with DB_URL/DB_USER/DB_PASSWORD from the root .env, which is not modified.
    This machine's ADC credential is found automatically and mounted read-only.

    One-time setup per machine, with an account holding the Vertex AI User role on
    the project:

        gcloud config set project <project-id>
        gcloud auth application-default login
        gcloud auth application-default set-quota-project <project-id>

    Extra arguments are passed as-is to `docker compose up`.

.EXAMPLE
    .\scripts\dev-gemini.ps1

.EXAMPLE
    .\scripts\dev-gemini.ps1 --build -d
#>
# Extra flags come from the automatic $args on purpose: a ValueFromRemainingArguments
# parameter makes this an advanced function, and PowerShell would then take `-d` as `-Debug`.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

# $IsWindows does not exist in Windows PowerShell 5.1, which only runs on Windows.
if ($null -eq $IsWindows -or $IsWindows) {
    $adcPath = Join-Path $env:APPDATA 'gcloud\application_default_credentials.json'
} else {
    $adcPath = Join-Path $HOME '.config/gcloud/application_default_credentials.json'
}

if (-not (Test-Path $adcPath)) {
    Write-Error "No se encontró la credencial ADC en $adcPath.`nCorré primero (con una cuenta con rol Usuario de Vertex AI sobre el proyecto):`n  gcloud config set project <project-id>`n  gcloud auth application-default login`n  gcloud auth application-default set-quota-project <project-id>"
}

$env:GOOGLE_ADC_HOST_PATH = $adcPath

# Overrides COMPOSE_PROFILES=ollama from .env so Ollama doesn't start. A sentinel rather
# than '': in PowerShell that deletes the variable and Compose falls back to .env. No
# service declares a "gemini" profile.
$env:COMPOSE_PROFILES = 'gemini'

# docker-compose.override.yml is left out on purpose: it belongs to the local stack.
$composeFiles = @('-f', 'docker-compose.railway.yml', '-f', 'docker-compose.gemini.yml')

Write-Host "Levantando Arbiter con Gemini (Vertex), contra la BD de Railway..." -ForegroundColor Cyan
Write-Host "Credencial: $adcPath" -ForegroundColor DarkGray
docker compose @composeFiles up @args
