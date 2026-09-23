<#
.SYNOPSIS
    Runs one Arbiter module locally with the variables from `.env` loaded.

.DESCRIPTION
    Spring Boot does not read .env files, so this loads them as environment variables
    for this process only, keeping credentials in one gitignored file.

.PARAMETER Module
    Module to start: auth-service, cases-service, classification-service,
    rules-service or reports-service.

.PARAMETER EnvFile
    Variables file. Defaults to `.env` at the repo root.

.EXAMPLE
    .\scripts\run-local.ps1 auth-service

.EXAMPLE
    # Only load and validate the variables, without starting anything:
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

# Plain KEY=VALUE only: no quotes, no multiline values.
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

if ([string]::IsNullOrWhiteSpace($env:DB_URL) -or $env:DB_URL -like '*HOST:PUERTO*') {
    Write-Error "DB_URL sin completar. Sacá host/puerto/base de DATABASE_PUBLIC_URL en Railway."
}
if ([string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
    Write-Error "JWT_SECRET vacío: sin esto no levanta el contexto de seguridad (H0003)."
}
if ($env:JWT_SECRET.Length -lt 32) {
    Write-Error "JWT_SECRET demasiado corto ($($env:JWT_SECRET.Length) chars). HS256 pide 32+ bytes."
}

# Host only, never credentials: this often runs while screen sharing.
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

# The JDBC driver sends the JVM time zone to the server. On Windows, Argentina resolves to
# the legacy `America/Buenos_Aires`, which Railway's Postgres rejects. UTC is safe because
# the columns are TIMESTAMPTZ.
mvn spring-boot:run -pl $Module "-Dspring-boot.run.jvmArguments=-Duser.timezone=UTC"
