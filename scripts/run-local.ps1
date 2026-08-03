<#
.SYNOPSIS
    Corre un módulo de Arbiter en local, con las variables de `.env` cargadas.

.DESCRIPTION
    Spring Boot no lee archivos .env por su cuenta: espera variables de entorno.
    Este script hace ese puente, así las credenciales viven en un solo archivo
    gitignoreado y no en un application.yml versionado ni en el historial de la
    terminal.

    Las variables se setean sólo para este proceso; no tocan el entorno del
    sistema ni quedan en otras terminales abiertas.

.PARAMETER Module
    Módulo a levantar: auth-service, cases-service, classification-service,
    rules-service o reports-service.

.PARAMETER EnvFile
    Archivo de variables. Por defecto `.env` en la raíz del repo.

.EXAMPLE
    .\scripts\run-local.ps1 auth-service

.EXAMPLE
    # Sólo cargar las variables y verificar la conexión, sin levantar nada:
    .\scripts\run-local.ps1 -CheckOnly
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('auth-service', 'cases-service', 'classification-service',
                 'rules-service', 'reports-service')]
    [string]$Module,

    [string]$EnvFile = '.env',

    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

if (-not (Test-Path $EnvFile)) {
    Write-Error "No existe $EnvFile. Copiá .env.example a .env y completá los valores."
}

# Parseo deliberadamente simple: KEY=VALUE, ignorando comentarios y líneas vacías.
# No soporta comillas ni multilínea — si una credencial las necesita, conviene
# pasarla por el entorno directamente en vez de complicar esto.
$loaded = 0
foreach ($line in Get-Content $EnvFile) {
    $trimmed = $line.Trim()
    if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }

    $separator = $trimmed.IndexOf('=')
    if ($separator -lt 1) { continue }

    $key = $trimmed.Substring(0, $separator).Trim()
    $value = $trimmed.Substring($separator + 1).Trim()
    Set-Item -Path "env:$key" -Value $value
    $loaded++
}
Write-Host "Cargadas $loaded variables desde $EnvFile" -ForegroundColor DarkGray

# Chequeos que evitan el 90% de los "no arranca y no dice por qué".
if ([string]::IsNullOrWhiteSpace($env:DB_URL) -or $env:DB_URL -like '*HOST:PUERTO*') {
    Write-Error "DB_URL sin completar. Sacá host/puerto/base de DATABASE_PUBLIC_URL en Railway."
}
if ([string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
    Write-Error "JWT_SECRET vacío: sin esto no levanta el contexto de seguridad (H0003)."
}
if ($env:JWT_SECRET.Length -lt 32) {
    Write-Error "JWT_SECRET demasiado corto ($($env:JWT_SECRET.Length) chars). HS256 pide 32+ bytes."
}

# El host se muestra sin credenciales a propósito: este script se corre con la
# terminal a la vista y compartiendo pantalla.
if ($env:DB_URL -match 'jdbc:postgresql://([^/?]+)') {
    Write-Host "Base de datos: $($Matches[1])" -ForegroundColor Cyan
}

if ($CheckOnly) {
    Write-Host "OK: variables cargadas y validadas. No se levantó ningún módulo." -ForegroundColor Green
    return
}

if (-not $Module) {
    Write-Error "Falta el módulo. Ej: .\scripts\run-local.ps1 cases-service"
}

Write-Host "Levantando $Module..." -ForegroundColor Cyan
mvn spring-boot:run -pl $Module
