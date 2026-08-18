# SMOKE TEST: uruchamia JEDEN test 7-dniowy z profilu A (Singiel wiosna).
# Cel: sprawdzic ze symulator + pipeline dziala zanim puscimy 24 testy na 30d.
#
# Czas realny: ~14 min (speedFactor 720, 7 dni sim).
#
# Uruchom:
#     .\run_smoke.ps1

$backendUrl = "http://localhost:8080/api/v1/tests"
$smokeFile  = Join-Path $PSScriptRoot "seasonal\A_singiel_biuro_wiosna.json"

Write-Host ""
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " SMOKE TEST - Profil A (Singiel wiosna) - 7 dni" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host ""

# Sprawdz backend
try {
    Invoke-WebRequest -Uri $backendUrl -Method GET -TimeoutSec 3 -ErrorAction Stop | Out-Null
    Write-Host "[OK] Backend zyje" -ForegroundColor Green
} catch {
    Write-Host "[BLAD] Backend niedostepny na http://localhost:8080" -ForegroundColor Red
    exit 1
}

# Wczytaj konfiguracje szablonu
$config = Get-Content -Path $smokeFile -Raw -Encoding UTF8 | ConvertFrom-Json

# Zmien durationDays z 30 na 7 (smoke)
$config.durationDays = 7
$config.name = $config.name + " [SMOKE 7d]"

Write-Host "Uruchamiam test:" -ForegroundColor Yellow
Write-Host "  Nazwa:         $($config.name)"
Write-Host "  Dni symulacji: $($config.durationDays)"
Write-Host "  Speed factor:  $($config.speedFactor)"
Write-Host "  Urzadzenia:    $($config.devices.Count)"
Write-Host "  Czas realny:   ~$([math]::Round($config.durationDays * 24 * 60 / $config.speedFactor)) min"
Write-Host ""

$payload = $config | ConvertTo-Json -Depth 20
$payloadBytes = [System.Text.Encoding]::UTF8.GetBytes($payload)

try {
    $response = Invoke-RestMethod -Uri $backendUrl -Method POST `
        -ContentType "application/json; charset=utf-8" -Body $payloadBytes

    Write-Host "[OK] Test utworzony!" -ForegroundColor Green
    Write-Host "  Test ID: $($response.testId)" -ForegroundColor Cyan
    Write-Host "  Status:  $($response.status)"
    Write-Host ""
    Write-Host "Otworz w przegladarce:" -ForegroundColor Yellow
    Write-Host "  http://localhost:5173/tests/$($response.testId)" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Poczekaj ~14 min, potem sprawdz:" -ForegroundColor Yellow
    Write-Host "  - Wykres mocy: powinno byc 7 dni danych"
    Write-Host "  - Sekcja Koszt: 168h zuzycia, 3 taryfy G11/G12/RDN"
    Write-Host ""
} catch {
    Write-Host "[FAIL] $($_.Exception.Message)" -ForegroundColor Red
    try {
        $stream = $_.Exception.Response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        Write-Host "BODY: $($reader.ReadToEnd())" -ForegroundColor Red
    } catch { }
    exit 1
}
