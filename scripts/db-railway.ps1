<#
.SYNOPSIS
    Corre los scripts de esquema contra la base de Railway, leyendo la conexión de `.env`.

.DESCRIPTION
    Reusa las mismas DB_URL / DB_USER / DB_PASSWORD que ya necesita Spring, así la
    credencial vive en un solo lugar gitignoreado y no se pega en la terminal (donde
    queda en el historial) ni en la línea de comandos (donde la ve cualquiera que
    liste procesos). La password viaja por PGPASSWORD, no dentro de la URL.

    DB_URL está en formato JDBC porque es lo que consume Spring; acá se le saca el
    prefijo `jdbc:` para que psql la entienda.

.PARAMETER Step
    check   Sólo verifica conexión y pgvector. No modifica nada.
    reset   DESTRUCTIVO: dropea todos los esquemas arbiter_* y aseguradora_*.
    init    Crea esquemas, tablas y catálogos.
    seed    Carga los datos de demo.
    verify  Confirma que quedó el esquema nuevo (chequea las columnas agregadas).
    all     check → reset → init → seed → verify, en orden.

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

    # Salta la confirmación del paso destructivo.
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

# psql no suele quedar en el PATH cuando se instala PostgreSQL en Windows.
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

# jdbc:postgresql://host:port/base?params  →  postgresql://host:port/base?params
$connectionUrl = $env:DB_URL -replace '^jdbc:', ''
$env:PGPASSWORD = $env:DB_PASSWORD

if ($connectionUrl -match '://([^/?]+)') {
    Write-Host "Base: $($Matches[1])  usuario: $env:DB_USER" -ForegroundColor Cyan
}

function Invoke-Psql {
    param([string[]]$PsqlArgs, [string]$Label)

    Write-Host "→ $Label" -ForegroundColor Cyan
    # ON_ERROR_STOP: sin esto psql sigue tras un error y termina con exit 0, que es
    # cómo se llega a un esquema a medio crear creyendo que salió bien.
    & $psql $connectionUrl -v ON_ERROR_STOP=1 -U $env:DB_USER @PsqlArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Error "$Label falló (exit $LASTEXITCODE). No sigo con los pasos siguientes."
    }
}

function Step-Check {
    Invoke-Psql @('-c', 'SELECT version();') 'Conexión'
    Write-Host '→ pgvector disponible?' -ForegroundColor Cyan
    $vector = & $psql $connectionUrl -U $env:DB_USER -tAc `
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

# Un solo query: devuelve "etiqueta|valor" por línea y acá se compara contra lo esperado.
function Get-Scalar {
    param([string]$Sql)
    return (& $psql $connectionUrl -U $env:DB_USER -tAc $Sql | Where-Object { $_ }) -join ''
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

    # 1. Estructura. init es un solo BEGIN/COMMIT: si abortó a mitad no queda nada,
    #    pero si se corrió una versión vieja del script el conteo va a diferir.
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
"@ 24
    }

    # 2. Que sea el esquema NUEVO y no el del commit anterior. Estas columnas se
    #    agregaron después de la última vez que se corrió contra Railway.
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

    # 3. Que los catálogos y el seed hayan entrado. Sin esto el esquema está bien
    #    pero la app no arranca ni tiene con qué probar.
    Assert-Count 'estados en arbiter_common.case_status' `
        'SELECT count(*) FROM arbiter_common.case_status;' 6
    Assert-Count 'aseguradoras registradas' `
        'SELECT count(*) FROM arbiter_common.insurer;' 2
    Assert-Count 'usuarios sembrados' `
        'SELECT count(*) FROM arbiter_common.users;' 4 -AtLeast
    Assert-Count 'casos en arbiter_bbva' `
        'SELECT count(*) FROM arbiter_bbva.cases;' 1 -AtLeast
    Assert-Count 'casos en arbiter_provincia' `
        'SELECT count(*) FROM arbiter_provincia.cases;' 1 -AtLeast

    # 4. El caso multi-aseguradora que hace falta para probar la agregación: una
    #    identidad en el esquema común con perfil de asegurado en las dos compañías.
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
