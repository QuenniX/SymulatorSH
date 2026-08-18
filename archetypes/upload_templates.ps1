# Skrypt do wsadowego uploadu profilow jako szablonow do backendu.
# Uruchom w PowerShell z folderu archetypes/:
#     .\upload_templates.ps1                                         # domyslnie localhost:8080
#     .\upload_templates.ps1 -Url "https://api.symulatorsh.pl"       # produkcja EC2
#     .\upload_templates.ps1 -Url "http://3.77.28.199:8080"          # bezposrednio po IP

param(
    [string]$Url = "http://localhost:8080"
)
$BackendHost = $Url

# Usun trailing slash jesli jest
$BackendHost = $BackendHost.TrimEnd('/')

$backendUrl = "$BackendHost/api/v1/templates"
# Bierzemy z folderu seasonal/ (24 wygenerowane warianty), nie z glowego folderu.
$profilesDir = Join-Path $PSScriptRoot "seasonal"

Write-Host ""
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " Upload profilow do bazy szablonow" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " Backend: $BackendHost" -ForegroundColor Cyan
Write-Host ""

# Sprawdz czy backend zyje
try {
    $health = Invoke-WebRequest -Uri "$BackendHost/api/v1/tests" -Method GET -TimeoutSec 10 -UseBasicParsing -ErrorAction Stop
    Write-Host "[OK] Backend odpowiada (HTTP $($health.StatusCode))" -ForegroundColor Green
} catch {
    Write-Host "[BLAD] Backend nie odpowiada na $BackendHost" -ForegroundColor Red
    Write-Host "        Sprawdz czy backend dziala i podaj poprawny URL:" -ForegroundColor Yellow
    Write-Host "        .\upload_templates.ps1 -Host `"https://twoja-domena`"" -ForegroundColor Yellow
    exit 1
}

# Wczytaj istniejace szablony (zeby nie duplikowac)
try {
    $existing = Invoke-RestMethod -Uri $backendUrl -Method GET
    $existingNames = @($existing | ForEach-Object { $_.name })
    Write-Host "[INFO] Istniejace szablony w bazie: $($existingNames.Count)" -ForegroundColor Gray
} catch {
    $existingNames = @()
}

Write-Host ""

# Znajdz wszystkie pliki .json (poza upload_templates.ps1)
$jsonFiles = Get-ChildItem -Path $profilesDir -Filter "*.json" | Sort-Object Name

if ($jsonFiles.Count -eq 0) {
    Write-Host "[BLAD] Brak plikow .json w $profilesDir" -ForegroundColor Red
    exit 1
}

Write-Host "Znaleziono $($jsonFiles.Count) profilow do uploadu." -ForegroundColor Cyan
Write-Host ""

$uploaded = 0
$skipped = 0
$failed = 0

foreach ($file in $jsonFiles) {
    # Wymuszamy UTF-8 przy odczycie - polskie znaki (l, e, a, s, z) inaczej
    # sie psuja bo Windows PowerShell 5.x domyslnie czyta jako Windows-1250.
    $rawContent = Get-Content -Path $file.FullName -Raw -Encoding UTF8
    $config = $rawContent | ConvertFrom-Json
    $templateName = $config.name
    $description = $config.description

    if ($existingNames -contains $templateName) {
        Write-Host "  [SKIP] '$templateName' juz istnieje" -ForegroundColor Yellow
        $skipped++
        continue
    }

    $payload = @{
        name = $templateName
        description = $description
        config = $config
    } | ConvertTo-Json -Depth 20

    try {
        # Wyslij jako UTF-8 bytes zeby polskie znaki dotarly nienaruszone.
        $payloadBytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
        $response = Invoke-RestMethod -Uri $backendUrl -Method POST `
            -ContentType "application/json; charset=utf-8" -Body $payloadBytes
        Write-Host "  [OK]   '$templateName'" -ForegroundColor Green
        $uploaded++
    } catch {
        Write-Host "  [FAIL] '$templateName' - $($_.Exception.Message)" -ForegroundColor Red
        $failed++
    }
}

Write-Host ""
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " Podsumowanie" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " Uploadowano:  $uploaded" -ForegroundColor Green
Write-Host " Pominieto:    $skipped" -ForegroundColor Yellow
Write-Host " Bledy:        $failed" -ForegroundColor $(if ($failed -gt 0) { "Red" } else { "Gray" })
Write-Host ""
Write-Host "Otworz aplikacje w przegladarce (Kreator) zeby zobaczyc szablony." -ForegroundColor Cyan
Write-Host ""
