# ===========================
#  AiTradeBot — Audit Collector
#  Collects only required files
# ===========================

Write-Host "=== AiTradeBot audit collector started ===" -ForegroundColor Cyan

$root = Get-Location
$outDir = Join-Path $root "audit_sources"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# ---------------------------
#  LIST OF FILES WE MUST COLLECT
# ---------------------------

$filesToFind = @(
# CHART
    "StrategyChartApiController.java",
    "WebChartFacadeImpl.java",
    "WebChartFacade.java",
    "StrategyChartDto.java",
    "strategy-chart.js",
    "dashboard.html",

    # CANDLES
    "MarketServiceImpl.java",
    "CandleProvider.java",
    "CandleServiceAdapter.java",
    "CandleWebSocketHandler.java",

    # STRATEGIES
    "SmartFusionStrategy.java",
    "FibonacciGridStrategy.java",
    "ScalpingStrategy.java",
    "MlInvestStrategy.java",
    "RsiEmaStrategy.java",

    # SETTINGS
    "StrategySettings.java",
    "StrategySettingsService.java",
    "StrategySettingsRepository.java",
    "StrategyDashboardController.java"
)

# ---------------------------
#  SEARCH AND COPY
# ---------------------------

foreach ($fileName in $filesToFind) {

    $found = Get-ChildItem -Path $root -Recurse -File -Filter $fileName -ErrorAction SilentlyContinue

    if ($found) {
        foreach ($f in $found) {

            # Build relative folder structure
            $relativeFolder = $f.DirectoryName.Replace($root, "").TrimStart("\")
            $targetFolder = Join-Path $outDir $relativeFolder

            New-Item -ItemType Directory -Force -Path $targetFolder | Out-Null

            Copy-Item $f.FullName -Destination $targetFolder -Force
            Write-Host "[OK] $fileName -> $targetFolder" -ForegroundColor Green
        }
    }
    else {
        Write-Host "[MISS] $fileName (not found)" -ForegroundColor Yellow
    }
}

Write-Host "========================================="
Write-Host "✔ Audit collection complete!" -ForegroundColor Cyan
Write-Host "✔ Output folder: $outDir" -ForegroundColor Cyan
Write-Host "========================================="
