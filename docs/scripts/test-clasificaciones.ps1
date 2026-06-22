#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Invoca el endpoint de clasificación con los escenarios de test.
  Muestra los resultados en una tabla legible.

.DESCRIPTION
  Lee los JSON fixtures desde src/test/resources/fixtures/
  y hace POST a http://localhost:8082/api/v1/clasificaciones

  Uso: ./test-clasificaciones.ps1
       ./test-clasificaciones.ps1 -BaseUrl http://custom-host:9000
#>

param(
    [string]$BaseUrl = "http://localhost:8082",
    [string]$Filter = ""  # ej: -Filter "fast-track" solo ejecuta ese escenario
)

$baseUrlTrimmed = $BaseUrl.TrimEnd('/')
$endpoint = "$baseUrlTrimmed/api/v1/clasificaciones"
$fixturesDir = "./siniestros-service/src/test/resources/fixtures"

if (-not (Test-Path $fixturesDir)) {
    Write-Error "No se encontró el directorio de fixtures: $fixturesDir"
    exit 1
}

$fixtures = @(
    "escenario-fast-track.json",
    "escenario-fast-track-2.json",
    "escenario-fast-track-3.json",
    "escenario-posible-riesgo.json",
    "escenario-posible-riesgo-2.json",
    "escenario-posible-riesgo-3.json",
    "escenario-falta-documentacion-1.json",
    "escenario-falta-documentacion-2.json",
    "escenario-analisis-manual-1.json",
    "escenario-analisis-manual-2.json",
    "escenario-sin-riesgo.json"
)

if ($Filter) {
    $fixtures = $fixtures | Where-Object { $_ -match $Filter }
}

Write-Host ""
Write-Host "╔════════════════════════════════════════════════════════════════╗"
Write-Host "║  PRUEBAS DE CLASIFICACIÓN — Arbiter v1.0                      ║"
Write-Host "╠════════════════════════════════════════════════════════════════╣"
Write-Host "║  Endpoint: $endpoint"
Write-Host "║  Fixtures: $($fixtures.Count) escenarios"
Write-Host "╚════════════════════════════════════════════════════════════════╝"
Write-Host ""

$resultados = @()
$exitosos = 0
$fallidos = 0

foreach ($fixtureFile in $fixtures) {
    $ruta = Join-Path $fixturesDir $fixtureFile

    if (-not (Test-Path $ruta)) {
        Write-Warning "Fixture no encontrado: $fixtureFile"
        continue
    }

    $fixture = Get-Content $ruta | ConvertFrom-Json
    $nombre = $fixture.nombre
    $esperada = $fixture.clasificacionEsperada
    $request = $fixture.request

    # Enriquecer el request con campos obligatorios que faltan en los fixtures
    $denunciaJson = @{
        ramo = $request.ramo
        producto = $request.producto
        hechoGenerador = $request.hechoGenerador
        bienAsegurado = $request.bienAsegurado
        aseguradoDni = "12.345.678"  # dummy
        polizaNumero = "POL-2026-001"  # dummy
        descripcionLibre = $request.descripcionLibre
        fechaHecho = [datetime]::Now.AddHours(-1).ToString("yyyy-MM-ddTHH:mm:ss")
        lugarHecho = "CABA, Argentina"
        adjuntosOCR = $request.adjuntosOCR
    } | ConvertTo-Json -Depth 10

    Write-Host "▶ $fixtureFile ... " -NoNewline

    try {
        $inicio = [DateTime]::Now
        $respuesta = Invoke-RestMethod -Uri $endpoint -Method Post `
            -ContentType "application/json" `
            -Body $denunciaJson `
            -ErrorAction Stop
        $latencia = ([DateTime]::Now - $inicio).TotalMilliseconds

        $clasificacion = $respuesta.clasificacion
        $confianza = $respuesta.confianza
        $factores = $respuesta.factores.Count

        $coincide = if ($clasificacion -eq $esperada) { "✓ SÍ" } else { "✗ NO" }
        $color = if ($clasificacion -eq $esperada) { "Green" } else { "Red" }

        Write-Host $coincide -ForegroundColor $color

        $resultado = [PSCustomObject]@{
            Fixture = $fixtureFile
            Nombre = $nombre
            Esperada = $esperada
            Obtenida = $clasificacion
            Confianza = [Math]::Round($confianza, 2)
            Factores = $factores
            Latencia = "${latencia}ms"
            Coincide = ($clasificacion -eq $esperada)
        }

        $resultados += $resultado

        if ($clasificacion -eq $esperada) {
            $exitosos++
        } else {
            $fallidos++
        }

    } catch {
        Write-Host "✗ ERROR" -ForegroundColor Red
        Write-Host "  → $($_.Exception.Message)" -ForegroundColor Yellow
        $fallidos++

        $resultado = [PSCustomObject]@{
            Fixture = $fixtureFile
            Nombre = $nombre
            Esperada = $esperada
            Obtenida = "ERROR"
            Confianza = "N/A"
            Factores = 0
            Latencia = "N/A"
            Coincide = $false
        }

        $resultados += $resultado
    }
}

Write-Host ""
Write-Host "╔════════════════════════════════════════════════════════════════╗"
Write-Host "║  RESUMEN                                                       ║"
Write-Host "╠════════════════════════════════════════════════════════════════╣"

$resultados | Format-Table -AutoSize @(
    @{ Label = "Esperada"; Expression = { $_.Esperada } },
    @{ Label = "Obtenida"; Expression = { $_.Obtenida } },
    @{ Label = "Confianza"; Expression = { $_.Confianza } },
    @{ Label = "Factores"; Expression = { $_.Factores } },
    @{ Label = "✓/✗"; Expression = { if ($_.Coincide) { "✓" } else { "✗" } }; Alignment = "Center" },
    @{ Label = "Latencia"; Expression = { $_.Latencia } }
)

Write-Host "╠════════════════════════════════════════════════════════════════╣"
Write-Host "║  Total: $($resultados.Count) | Exitosos: $exitosos | Fallidos: $fallidos"
Write-Host "╚════════════════════════════════════════════════════════════════╝"
Write-Host ""

if ($fallidos -gt 0) {
    Write-Host "Detalles de fallos:" -ForegroundColor Yellow
    $resultados | Where-Object { -not $_.Coincide } | ForEach-Object {
        Write-Host "  • $($_.Nombre)"
        Write-Host "    Esperada: $($_.Esperada) | Obtenida: $($_.Obtenida)"
    }
    Write-Host ""
    exit 1
} else {
    Write-Host "✓ Todos los escenarios pasaron correctamente." -ForegroundColor Green
    exit 0
}
