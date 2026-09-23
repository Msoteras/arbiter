<#
.SYNOPSIS
    Runs the schema scripts against the Railway database, reading the connection from `.env`.

.DESCRIPTION
    Reuses Spring's DB_URL / DB_USER / DB_PASSWORD, so the credential stays in one
    gitignored file, out of the shell history and the process list. The password is
    passed through PGPASSWORD, never in the URL.

.PARAMETER Step
    check   Checks the connection and pgvector only. Changes nothing.
    reset   DESTRUCTIVE: drops every arbiter_* and aseguradora_* schema.
    init    Creates schemas, tables and catalogs.
    seed    Loads the demo data.
    verify  Checks the resulting schema and seed.
    all     check → reset → init → seed → verify, in order.

.EXAMPLE
    .\scripts\db-railway.ps1 check

.EXAMPLE
    .\scripts\db-railway.ps1 all
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('check', 'reset', 'init', 'seed', 'verify', 'all')]
    [string]$Step = 'check',

    [string]$EnvFile = '.env',

    # Skips the confirmation of the destructive step.
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

# The Windows PostgreSQL installer usually leaves psql off the PATH.
$psql = (Get-Command psql -ErrorAction SilentlyContinue).Source
if (-not $psql) {
    $psql = Get-ChildItem 'C:\Program Files\PostgreSQL' -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'bin\psql.exe' } |
        Where-Object { Test-Path $_ } |
        Select-Object -Last 1
}
if (-not $psql) {
    Write-Error "No encuentro psql.exe. Instalalo o agregá su carpeta bin al PATH."
}

if (-not (Test-Path $EnvFile)) {
    Write-Error "No existe $EnvFile. Copiá .env.example a .env y completá los valores."
}

foreach ($line in Get-Content $EnvFile) {
    $trimmed = $line.Trim()
    if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
    $separator = $trimmed.IndexOf('=')
    if ($separator -lt 1) { continue }
    Set-Item -Path "env:$($trimmed.Substring(0, $separator).Trim())" `
             -Value $trimmed.Substring($separator + 1).Trim()
}

if ([string]::IsNullOrWhiteSpace($env:DB_URL) -or $env:DB_URL -like '*HOST:PUERTO*') {
    Write-Error "DB_URL sin completar en $EnvFile."
}

# jdbc:postgresql://host:port/db?params  →  postgresql://host:port/db?params
$connectionUrl = $env:DB_URL -replace '^jdbc:', ''

# Without connect_timeout psql waits forever on a wrong host or a blocked port.
if ($connectionUrl -notmatch 'connect_timeout=') {
    $connectionUrl += $(if ($connectionUrl -like '*?*') { '&' } else { '?' }) + 'connect_timeout=10'
}

if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
    # psql would otherwise prompt for it and hang, indistinguishable from a network stall.
    Write-Error "DB_PASSWORD vacio en $EnvFile. psql se quedaria esperando el prompt."
}
$env:PGPASSWORD = $env:DB_PASSWORD

if ($connectionUrl -match '://([^/?]+)') {
    Write-Host "Base: $($Matches[1])  usuario: $env:DB_USER" -ForegroundColor Cyan
}

# Always pass the connection with -d: Windows psql stops parsing options at the first
# positional argument, silently ignoring everything after the URL.
function Invoke-Psql {
    param([string[]]$PsqlArgs, [string]$Label)

    Write-Host "→ $Label" -ForegroundColor Cyan
    # PowerShell 5.1 turns each stderr line of a native command into an ErrorRecord, so
    # under Stop a psql NOTICE would abort the script. Success is judged by $LASTEXITCODE.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        # Without ON_ERROR_STOP psql continues past errors and exits 0.
        & $psql -d $connectionUrl -U $env:DB_USER -v ON_ERROR_STOP=1 @PsqlArgs
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($LASTEXITCODE -ne 0) {
        Write-Error "$Label falló (exit $LASTEXITCODE). No sigo con los pasos siguientes."
    }
}

function Step-Check {
    Invoke-Psql @('-c', 'SELECT version();') 'Conexión'
    Write-Host '→ pgvector disponible?' -ForegroundColor Cyan
    $vector = & $psql -d $connectionUrl -U $env:DB_USER -tAc `
        "SELECT name FROM pg_available_extensions WHERE name='vector';"
    if (-not $vector) {
        Write-Error "pgvector no está disponible. init-multitenant.sql lo crea en su primera sentencia, así que sin esto no arranca. Habilitalo desde la consola de Railway."
    }
    Write-Host "  pgvector: OK" -ForegroundColor Green
}

function Step-Reset {
    if (-not $Force) {
        Write-Host ''
        Write-Warning "reset-multitenant.sql BORRA todos los esquemas arbiter_* y aseguradora_* de esta base."
        $answer = Read-Host "Escribí 'borrar' para confirmar"
        if ($answer -ne 'borrar') {
            Write-Error 'Cancelado.'
        }
    }
    Invoke-Psql @('-f', 'db/reset-multitenant.sql') 'Reset (destructivo)'
}

function Get-Scalar {
    param([string]$Sql)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return (& $psql -d $connectionUrl -U $env:DB_USER -tAc $Sql | Where-Object { $_ }) -join ''
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Assert-Count {
    param([string]$Label, [string]$Sql, [int]$Expected, [switch]$AtLeast)

    $actual = [int](Get-Scalar $Sql)
    $ok = if ($AtLeast) { $actual -ge $Expected } else { $actual -eq $Expected }
    $suffix = if ($AtLeast) { "(esperado >= $Expected)" } else { "(esperado $Expected)" }

    if ($ok) {
        Write-Host ("  {0,-46} {1,4}  OK" -f $Label, $actual) -ForegroundColor Green
    } else {
        Write-Host ("  {0,-46} {1,4}  MAL $suffix" -f $Label, $actual) -ForegroundColor Red
        $script:verifyFailed = $true
    }
}

function Step-Verify {
    $script:verifyFailed = $false
    Write-Host '→ Verificación' -ForegroundColor Cyan

    # 1. Structure.
    Assert-Count 'esquemas creados' @"
SELECT count(*) FROM information_schema.schemata
 WHERE schema_name = 'arbiter_common'
    OR schema_name LIKE 'arbiter\_%' OR schema_name LIKE 'aseguradora\_%';
"@ 5

    Assert-Count 'tablas en arbiter_common' @"
SELECT count(*) FROM information_schema.tables WHERE table_schema='arbiter_common';
"@ 10

    foreach ($tenant in @('arbiter_bbva', 'arbiter_provincia')) {
        Assert-Count "tablas en $tenant" @"
SELECT count(*) FROM information_schema.tables WHERE table_schema='$tenant';
"@ 33
    }

    # 2. Columns that only a current init script creates.
    Assert-Count 'columnas nuevas en arbiter_bbva.cases' @"
SELECT count(*) FROM information_schema.columns
 WHERE table_schema='arbiter_bbva' AND table_name='cases'
   AND column_name IN ('risk_score','risk_band','forensic_report',
                       'classification_attempts','manual_adjustment_note');
"@ 5

    Assert-Count 'actor en case_status_history' @"
SELECT count(*) FROM information_schema.columns
 WHERE table_schema='arbiter_bbva' AND table_name='case_status_history'
   AND column_name='actor';
"@ 1

    Assert-Count 'image_consent en insured' @"
SELECT count(*) FROM information_schema.columns
 WHERE table_schema='arbiter_bbva' AND table_name='insured' AND column_name='image_consent';
"@ 1

    Assert-Count 'model en image_analysis' @"
SELECT count(*) FROM information_schema.columns
 WHERE table_schema='arbiter_bbva' AND table_name='image_analysis' AND column_name='model';
"@ 1

    Assert-Count 'email único en arbiter_common.users' @"
SELECT count(*) FROM pg_indexes
 WHERE schemaname='arbiter_common' AND indexname='users_email_lower_uq';
"@ 1

    # 3. Catalogs and seed data.
    Assert-Count 'estados en arbiter_common.case_status' `
        'SELECT count(*) FROM arbiter_common.case_status;' 9
    Assert-Count 'aseguradoras registradas' `
        'SELECT count(*) FROM arbiter_common.insurer;' 2
    Assert-Count 'usuarios sembrados' `
        'SELECT count(*) FROM arbiter_common.users;' 4 -AtLeast
    Assert-Count 'casos en arbiter_bbva' `
        'SELECT count(*) FROM arbiter_bbva.cases;' 1 -AtLeast
    Assert-Count 'casos en arbiter_provincia' `
        'SELECT count(*) FROM arbiter_provincia.cases;' 1 -AtLeast

    # 4. One insured with a profile at both insurers, needed to test cross-tenant aggregation.
    Assert-Count 'asegurados en las DOS aseguradoras (caso Martina)' @"
SELECT count(*) FROM (
    SELECT dni FROM arbiter_bbva.insured
    INTERSECT
    SELECT dni FROM arbiter_provincia.insured
) AS ambas;
"@ 1 -AtLeast

    if ($script:verifyFailed) {
        Write-Error 'La verificación encontró diferencias. La base NO quedó como se espera.'
    }
    Write-Host '  Todo OK: esquema nuevo, catálogos y datos de demo cargados.' -ForegroundColor Green
}

switch ($Step) {
    'check'  { Step-Check }
    'reset'  { Step-Reset }
    'init'   { Invoke-Psql @('-f', 'db/init-multitenant.sql') 'Init' }
    'seed'   { Invoke-Psql @('-f', 'db/seed-demo.sql') 'Seed' }
    'verify' { Step-Verify }
    'all'    {
        Step-Check
        Step-Reset
        Invoke-Psql @('-f', 'db/init-multitenant.sql') 'Init'
        Invoke-Psql @('-f', 'db/seed-demo.sql') 'Seed'
        Step-Verify
    }
}

Write-Host "Listo: $Step" -ForegroundColor Green
