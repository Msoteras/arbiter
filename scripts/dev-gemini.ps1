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
    Write-Error "No se encontró la credencial ADC en $adcPath.`nCorré primero (con una cuenta que tenga permiso sobre el proyecto de Vertex):`n  gcloud auth application-default login"
}

$env:GOOGLE_ADC_HOST_PATH = $adcPath

# El .env trae COMPOSE_PROFILES=ollama (así lo indica .env.example, porque es lo que
# necesita el modo Ollama), y Compose lo lee de ahí si no está en el entorno: sin pisarlo,
# este script levantaría Ollama igual — 6 GB de pesos y ~11 GB de RAM al pedo.
# El valor es un centinela, no cadena vacía: en PowerShell `$env:VAR = ''` BORRA la
# variable en vez de dejarla vacía, y Compose vuelve a caer en el .env. Ningún servicio
# declara el perfil "gemini", así que activarlo no levanta nada extra.
$env:COMPOSE_PROFILES = 'gemini'

# Al pasar -f explícito, Compose deja de autoincluir docker-compose.override.yml —
# hay que sumarlo a mano si existe, o quien lo use para apuntar a Railway (u otro
# ajuste local) termina levantando contra el Postgres local por accidente.
$composeFiles = @('-f', 'docker-compose.yml')
if (Test-Path 'docker-compose.override.yml') {
    $composeFiles += @('-f', 'docker-compose.override.yml')
}
$composeFiles += @('-f', 'docker-compose.gemini.yml')

Write-Host "Levantando Arbiter con Gemini (Vertex)..." -ForegroundColor Cyan
Write-Host "Credencial: $adcPath" -ForegroundColor DarkGray
docker compose @composeFiles up @args
