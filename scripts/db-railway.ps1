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

function Step-Verify {
    # init es un solo BEGIN/COMMIT: si abortó a mitad, no quedan estas columnas.
    $expected = @('classification_attempts', 'forensic_report', 'manual_adjustment_note',
                  'risk_band', 'risk_score')
    $found = & $psql $connectionUrl -U $env:DB_USER -tAc @"
SELECT column_name FROM information_schema.columns
 WHERE table_schema='arbiter_bbva' AND table_name='cases'
   AND column_name IN ('risk_score','risk_band','forensic_report',
                       'classification_attempts','manual_adjustment_note')
 ORDER BY column_name;
"@
    $foundList = @($found | Where-Object { $_ })
    if ($foundList.Count -ne $expected.Count) {
        Write-Error "Esperaba $($expected.Count) columnas nuevas en arbiter_bbva.cases y encontré $($foundList.Count): $($foundList -join ', '). La base quedó con un esquema viejo o el init abortó."
    }
    Write-Host "  esquema nuevo confirmado ($($foundList.Count)/$($expected.Count) columnas)" -ForegroundColor Green
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
