<#
.SYNOPSIS
    Levanta Arbiter contra la BD de Railway, con Gemini (Vertex AI) como motor
    de clasificación.

.DESCRIPTION
    Usa docker-compose.railway.yml, así que NO levanta Postgres local: los
    módulos apuntan a la base compartida de Railway (DB_URL/DB_USER/DB_PASSWORD
    del .env de la raíz). Tampoco levanta Ollama — te ahorrás ~11 GB de RAM.

    Resuelve sola la credencial ADC de esta máquina y la monta de solo lectura.
    No toca tu .env.

    Prerequisito, una sola vez por máquina, con una cuenta con rol Usuario de
    Vertex AI sobre el proyecto:

        gcloud config set project <project-id>
        gcloud auth application-default login
        gcloud auth application-default set-quota-project <project-id>

    Cualquier argumento extra se pasa tal cual a `docker compose up`.

.EXAMPLE
    .\scripts\dev-gemini.ps1

.EXAMPLE
    .\scripts\dev-gemini.ps1 --build -d
#>
# Los flags extra se leen del $args automático a propósito, sin declarar un parámetro con
# ValueFromRemainingArguments: eso convierte al script en advanced function y le suma los
# common parameters, y ahí PowerShell se queda con `-d` porque es prefijo único de `-Debug`.
# El flag nunca llegaba a Compose y el stack arrancaba en foreground creyendo estar detached.
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
    Write-Error "No se encontró la credencial ADC en $adcPath.`nCorré primero (con una cuenta con rol Usuario de Vertex AI sobre el proyecto):`n  gcloud config set project <project-id>`n  gcloud auth application-default login`n  gcloud auth application-default set-quota-project <project-id>"
}

$env:GOOGLE_ADC_HOST_PATH = $adcPath

# El .env trae COMPOSE_PROFILES=ollama (así lo indica .env.example, porque es lo que
# necesita el modo Ollama), y Compose lo lee de ahí si no está en el entorno: sin pisarlo,
# este script levantaría Ollama igual — 6 GB de pesos y ~11 GB de RAM al pedo.
# El valor es un centinela, no cadena vacía: en PowerShell `$env:VAR = ''` BORRA la
# variable en vez de dejarla vacía, y Compose vuelve a caer en el .env. Ningún servicio
# declara el perfil "gemini", así que activarlo no levanta nada extra.
$env:COMPOSE_PROFILES = 'gemini'

# docker-compose.override.yml queda deliberadamente afuera: es el override del stack
# LOCAL (docker-compose.yml), y lo que trae —redirigir DB_URL a Railway— ya lo hace este
# compose de entrada. Para ajustes propios de este stack existe docker-compose.railway.override.yml.
$composeFiles = @('-f', 'docker-compose.railway.yml', '-f', 'docker-compose.gemini.yml')

Write-Host "Levantando Arbiter con Gemini (Vertex), contra la BD de Railway..." -ForegroundColor Cyan
Write-Host "Credencial: $adcPath" -ForegroundColor DarkGray
docker compose @composeFiles up @args
