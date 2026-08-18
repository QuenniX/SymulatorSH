# BATCH RUN: uruchamia wszystkie 24 profile jako 30-dniowe testy.
# Backend z pool-size 12 wykona je 12 rownolegle -> 2 batche -> ~2h realnie.
#
# UWAGA: to duzo pomiarow do InfluxDB. Uruchamiaj tylko jak smoke test przeszedl.
#
# Uruchom:
#     .\run_all_30d.ps1
#
# Aby anulowac zakolejkowane testy: DELETE /tests/{id} lub przez UI.

$backendUrl = "http://localhost:8080/api/v1/tests"
$seasonalDir = Join-Path $PSScriptRoot "seasonal"

Write-Host ""
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " BATCH RUN - 24 profile x 30 dni" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host ""

# Sprawdz backend
try {
    Invoke-WebRequest -Uri $backendUrl -Method GET -TimeoutSec 3 -ErrorAction Stop | Out-Null
    Write-Host "[OK] Backend zyje" -ForegroundColor Green
} catch {
    Write-Host "[BLAD] Backend niedostepny" -ForegroundColor Red
    exit 1
}

$jsonFiles = Get-ChildItem -Path $seasonalDir -Filter "*.json" | Sort-Object Name
if ($jsonFiles.Count -eq 0) {
    Write-Host "[BLAD] Brak plikow w $seasonalDir" -ForegroundColor Red
    exit 1
}

Write-Host "Znaleziono $($jsonFiles.Count) profilow." -ForegroundColor Cyan
Write-Host ""
Write-Host "UWAGA: ten skrypt zakolejkuje $($jsonFiles.Count) testow na 30 dni symulacji." -ForegroundColor Yellow
Write-Host "  Szacowany czas: ~2 godziny (przy pool-size 12)." -ForegroundColor Yellow
Write-Host "  InfluxDB dostanie ~1M pomiarow." -ForegroundColor Yellow
Write-Host ""
$confirm = Read-Host "Kontynuowac? [tak/nie]"
if ($confirm -notmatch "^(tak|t|yes|y)$") {
    Write-Host "Anulowano." -ForegroundColor Yellow
    exit 0
}

Write-Host ""
$created = 0
$failed = 0
$testIds = @()

foreach ($file in $jsonFiles) {
    $config = Get-Content -Path $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json

    # Upewnij sie ze durationDays = 30 (baseline production)
    $config.durationDays = 30

    $payload = $config | ConvertTo-Json -Depth 20
    $payloadBytes = [System.Text.Encoding]::UTF8.GetBytes($payload)

    try {
        $response = Invoke-RestMethod -Uri $backendUrl -Method POST `
            -ContentType "application/json; charset=utf-8" -Body $payloadBytes
        Write-Host "  [OK]  $($config.name) -> $($response.testId)" -ForegroundColor Green
        $testIds += $response.testId
        $created++
    } catch {
        Write-Host "  [FAIL] $($config.name) - $($_.Exception.Message)" -ForegroundColor Red
        $failed++
    }
}

Write-Host ""
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " Podsumowanie" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " Utworzono: $created" -ForegroundColor Green
Write-Host " Bledy:     $failed" -ForegroundColor $(if ($failed -gt 0) { "Red" } else { "Gray" })
Write-Host ""
Write-Host "Otworz lista testow: http://localhost:5173/" -ForegroundColor Cyan
Write-Host ""
Write-Host "Testy sa wykonywane w tle. Sprawdzaj status na liscie." -ForegroundColor Yellow
Write-Host "Przy pool-size 12: pierwsze 12 startuje od razu, kolejne po zakonczeniu." -ForegroundColor Yellow
Write-Host ""

# Zapisz test IDs do pliku - do pozniejszej analizy zbiorczej
$testIds | Out-File -FilePath "batch_test_ids.txt" -Encoding utf8
Write-Host "ID zapisano do: batch_test_ids.txt" -ForegroundColor Gray
