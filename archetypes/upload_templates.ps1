# Synchronizuje profile z folderu seasonal/ z baza szablonow backendu.
#
#     .\upload_templates.ps1                                    # domyslnie localhost:8080
#     .\upload_templates.ps1 -Url "http://3.77.28.199"          # produkcja EC2
#     .\upload_templates.ps1 -Url "http://3.77.28.199" -DryRun  # tylko pokaz co zrobi
#
# UWAGA - zmiana zachowania (18.08.2026):
# Poprzednia wersja POMIJALA szablon, jesli nazwa juz istniala w bazie. Efekt: po
# poprawkach w JSON-ach (cykle 43, harmonogramy przez polnoc, odwrocone pary ON/OFF)
# baza dalej trzymala STARE konfiguracje, a partia testow leciala na nich mimo
# wdrozonego backendu. Teraz skrypt SYNCHRONIZUJE: kasuje wszystkie szablony
# "Profil X: ..." i wgrywa je od nowa z plikow. Szablony wlasne uzytkownika
# (nazwy nie zaczynajace sie od "Profil ") nie sa ruszane.

param(
    [string]$Url = "http://localhost:8080",
    [switch]$DryRun
)

$BackendHost = $Url.TrimEnd('/')
$backendUrl  = "$BackendHost/api/v1/templates"
$profilesDir = Join-Path $PSScriptRoot "seasonal"

Write-Host ""
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " Synchronizacja profili z baza szablonow" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " Backend: $BackendHost" -ForegroundColor Cyan
if ($DryRun) { Write-Host " TRYB: DRY-RUN (nic nie zostanie zmienione)" -ForegroundColor Yellow }
Write-Host ""

try {
    $health = Invoke-WebRequest -Uri "$BackendHost/api/v1/tests" -Method GET -TimeoutSec 10 -UseBasicParsing -ErrorAction Stop
    Write-Host "[OK] Backend odpowiada (HTTP $($health.StatusCode))" -ForegroundColor Green
} catch {
    Write-Host "[BLAD] Backend nie odpowiada na $BackendHost" -ForegroundColor Red
    exit 1
}

$jsonFiles = Get-ChildItem -Path $profilesDir -Filter "*.json" | Sort-Object Name
if ($jsonFiles.Count -eq 0) {
    Write-Host "[BLAD] Brak plikow .json w $profilesDir" -ForegroundColor Red
    exit 1
}
Write-Host "[INFO] Plikow do wgrania: $($jsonFiles.Count)" -ForegroundColor Gray

try {
    $existing = @(Invoke-RestMethod -Uri $backendUrl -Method GET)
} catch {
    $existing = @()
}
$stale = @($existing | Where-Object { $_.name -like "Profil *" })
Write-Host "[INFO] Szablonow w bazie: $($existing.Count), w tym profilowych do podmiany: $($stale.Count)" -ForegroundColor Gray
Write-Host ""

# ---- Krok 1: skasuj stare szablony profilowe ----
$deleted = 0
foreach ($t in $stale) {
    if ($DryRun) {
        Write-Host "  [DRY] skasowalbym '$($t.name)'" -ForegroundColor DarkGray
        $deleted++
        continue
    }
    try {
        Invoke-RestMethod -Uri "$backendUrl/$($t.templateId)" -Method DELETE | Out-Null
        Write-Host "  [DEL]  '$($t.name)'" -ForegroundColor DarkYellow
        $deleted++
    } catch {
        Write-Host "  [FAIL] nie udalo sie skasowac '$($t.name)' - $($_.Exception.Message)" -ForegroundColor Red
    }
}
if ($deleted -gt 0) { Write-Host "" }

# ---- Krok 2: wgraj aktualne ----
$uploaded = 0
$failed   = 0
foreach ($file in $jsonFiles) {
    # Wymuszamy UTF-8 przy odczycie - Windows PowerShell 5.x domyslnie czyta Windows-1250.
    $rawContent = Get-Content -Path $file.FullName -Raw -Encoding UTF8
    $config = $rawContent | ConvertFrom-Json

    if ($DryRun) {
        Write-Host "  [DRY]  wgralbym '$($config.name)'" -ForegroundColor DarkGray
        $uploaded++
        continue
    }

    $payload = @{
        name        = $config.name
        description = $config.description
        config      = $config
    } | ConvertTo-Json -Depth 20

    try {
        $payloadBytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
        Invoke-RestMethod -Uri $backendUrl -Method POST `
            -ContentType "application/json; charset=utf-8" -Body $payloadBytes | Out-Null
        Write-Host "  [OK]   '$($config.name)'" -ForegroundColor Green
        $uploaded++
    } catch {
        Write-Host "  [FAIL] '$($config.name)' - $($_.Exception.Message)" -ForegroundColor Red
        $failed++
    }
}

Write-Host ""
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " Podsumowanie" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " Skasowano starych: $deleted"  -ForegroundColor DarkYellow
Write-Host " Wgrano nowych:     $uploaded" -ForegroundColor Green
Write-Host " Bledy:             $failed"   -ForegroundColor $(if ($failed -gt 0) { "Red" } else { "Gray" })
Write-Host ""

# ---- Krok 3: weryfikacja ----
if (-not $DryRun) {
    try {
        $after = @(Invoke-RestMethod -Uri $backendUrl -Method GET)
        $profileTemplates = @($after | Where-Object { $_.name -like "Profil *" })
        Write-Host " Weryfikacja: w bazie jest $($profileTemplates.Count) szablonow profilowych (oczekiwano $($jsonFiles.Count))" -ForegroundColor Cyan
        if ($profileTemplates.Count -ne $jsonFiles.Count) {
            Write-Host " [UWAGA] Liczba sie nie zgadza - sprawdz liste w Kreatorze przed puszczeniem partii." -ForegroundColor Red
        }
        $dink = @($profileTemplates | Where-Object { $_.name -match "DINK|Remote worker" })
        if ($dink.Count -gt 0) {
            Write-Host " [UWAGA] W bazie zostaly stare nazwy angielskie:" -ForegroundColor Red
            $dink | ForEach-Object { Write-Host "         - $($_.name)" -ForegroundColor Red }
        }
    } catch {
        Write-Host " [UWAGA] Nie udalo sie zweryfikowac stanu bazy." -ForegroundColor Yellow
    }
}
Write-Host ""
