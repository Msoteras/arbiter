<#
.SYNOPSIS
    Levanta Arbiter completo con Gemini (Vertex AI) como motor de clasificación.

.DESCRIPTION
    Resuelve la credencial ADC de esta máquina y arranca con
    docker-compose.gemini.yml combinado sobre el compose base. No levanta
    Ollama (te ahorrás ~11 GB de RAM), y no toca tu .env.

    Prerequisito, una sola vez por máquina, con una cuenta que tenga permiso
    sobre el proyecto de Vertex:

        gcloud auth application-default login

    Cualquier argumento extra se pasa tal cual a `docker compose up`.

.EXAMPLE
    .\scripts\dev-gemini.ps1

.EXAMPLE
    .\scripts\dev-gemini.ps1 --build -d
#>
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ComposeArgs
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

# $IsWindows no existe en Windows PowerShell 5.1 (siempre Windows ahí); sólo hace
# falta discriminar cuando corre PowerShell Core en Mac/Linux.
if ($null -eq $IsWindows -or $IsWindows) {
    $adcPath = Join-Path $env:APPDATA 'gcloud\application_default_credentials.json'
} else {
    $adcPath = Join-Path $HOME '.config/gcloud/application_default_credentials.json'
}

if (-not (Test-Path $adcPath)) {
    Write-Error "No se encontró la credencial ADC en $adcPath.`nCorré primero (con una cuenta que tenga permiso sobre el proyecto de Vertex):`n  gcloud auth application-default login"
}

$env:GOOGLE_ADC_HOST_PATH = $adcPath

Write-Host "Levantando Arbiter con Gemini (Vertex)..." -ForegroundColor Cyan
Write-Host "Credencial: $adcPath" -ForegroundColor DarkGray
docker compose -f docker-compose.yml -f docker-compose.gemini.yml up @ComposeArgs
