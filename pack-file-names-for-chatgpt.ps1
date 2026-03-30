param(
    [string]$OutputName = "chatgpt_bundle.txt"
)

$ErrorActionPreference = "Stop"

# =========================================================
# 1) КОРЕНЬ ПРОЕКТА
#    Запускай скрипт из корня проекта
# =========================================================
$ProjectRoot = (Get-Location).Path
$ExportDir   = Join-Path $ProjectRoot "_chatgpt_bundle"
$OutputFile  = Join-Path $ExportDir $OutputName
$Manifest    = Join-Path $ExportDir "manifest.txt"

# =========================================================
# 2) ВСТАВЛЯЙ СЮДА ИМЕНА ФАЙЛОВ
#    ТОЛЬКО ИМЕНА ФАЙЛОВ, БЕЗ ПУТЕЙ
#
#    Пример:
#    "MlClient.java",
#    "TradeExecutionServiceImpl.java",
#    "app.py"
# =========================================================
$FileNamesToPack = @(
    "WindowScalpingStrategyV4.java",
    "MlAutoTuneRuntime.java",
    "TradeExecutionServiceImpl.java",
    "InMemoryPositionStoreImpl.java",
    "MlDatasetCollector.java",
    "TradeClosedEvent.java",
    "AiStrategyOrchestrator.java",
    "StrategySettings.java"
)

# =========================================================
# 3) ПОДГОТОВКА ПАПКИ РЕЗУЛЬТАТА
# =========================================================
if (Test-Path $ExportDir) {
    Remove-Item $ExportDir -Recurse -Force
}
New-Item -ItemType Directory -Path $ExportDir | Out-Null

# =========================================================
# 4) ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ
# =========================================================
function Get-RelativePathSafe {
    param(
        [string]$FullPath
    )

    try {
        $rootUri = [System.Uri]::new(($ProjectRoot.TrimEnd('\') + '\'))
        $fileUri = [System.Uri]::new($FullPath)
        $relative = $rootUri.MakeRelativeUri($fileUri).ToString()
        return [System.Uri]::UnescapeDataString($relative).Replace('/', '\')
    }
    catch {
        return $FullPath
    }
}

function Is-SkippedPath {
    param(
        [string]$Path
    )

    if ($Path -match '\\target\\') { return $true }
    if ($Path -match '\\build\\') { return $true }
    if ($Path -match '\\out\\') { return $true }
    if ($Path -match '\\\.git\\') { return $true }
    if ($Path -match '\\\.idea\\') { return $true }
    if ($Path -match '\\node_modules\\') { return $true }

    return $false
}

# =========================================================
# 5) СОЗДАЁМ ВЫХОДНЫЕ ФАЙЛЫ
# =========================================================
@(
    "CHATGPT FILE BUNDLE"
    "PROJECT_ROOT: $ProjectRoot"
    "CREATED_AT: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    ""
) | Set-Content -Path $OutputFile -Encoding UTF8

@(
    "MANIFEST"
    "PROJECT_ROOT: $ProjectRoot"
    "CREATED_AT: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    ""
) | Set-Content -Path $Manifest -Encoding UTF8

# =========================================================
# 6) ПОИСК ФАЙЛОВ ПО ИМЕНИ
# =========================================================
$ResolvedFiles = [System.Collections.Generic.List[string]]::new()
$MissingFiles  = [System.Collections.Generic.List[string]]::new()
$DuplicateHits = [System.Collections.Generic.List[string]]::new()

foreach ($fileName in $FileNamesToPack) {
    if ([string]::IsNullOrWhiteSpace($fileName)) {
        continue
    }

    $matches = Get-ChildItem -Path $ProjectRoot -Recurse -File -Filter $fileName -ErrorAction SilentlyContinue |
            Where-Object { -not (Is-SkippedPath -Path $_.FullName) } |
            Select-Object -ExpandProperty FullName -Unique

    if (-not $matches) {
        $MissingFiles.Add($fileName)
        continue
    }

    if (@($matches).Count -gt 1) {
        $DuplicateHits.Add($fileName)

        Add-Content -Path $Manifest -Encoding UTF8 -Value ("DUPLICATE_NAME: " + $fileName)
        foreach ($m in $matches) {
            Add-Content -Path $Manifest -Encoding UTF8 -Value ("  -> " + (Get-RelativePathSafe -FullPath $m))
        }
    }

    $selected = @($matches)[0]

    if (-not $ResolvedFiles.Contains($selected)) {
        $ResolvedFiles.Add($selected)
    }
}

# =========================================================
# 7) ЕСЛИ НИЧЕГО НЕ НАЙДЕНО
# =========================================================
if ($ResolvedFiles.Count -eq 0) {
    Write-Host ""
    Write-Host "Не найден ни один файл из списка." -ForegroundColor Red
    Write-Host "Проверь блок 'ВСТАВЛЯЙ СЮДА ИМЕНА ФАЙЛОВ'." -ForegroundColor Yellow
    Write-Host ""
    exit 1
}

# =========================================================
# 8) СОБИРАЕМ ОДИН ОБЩИЙ ФАЙЛ
# =========================================================
$count = 0

foreach ($file in $ResolvedFiles) {
    $relative = Get-RelativePathSafe -FullPath $file
    $item = Get-Item -LiteralPath $file

    Add-Content -Path $Manifest -Encoding UTF8 -Value ("FILE: " + $relative)

    Add-Content -Path $OutputFile -Encoding UTF8 -Value ""
    Add-Content -Path $OutputFile -Encoding UTF8 -Value ("=" * 120)
    Add-Content -Path $OutputFile -Encoding UTF8 -Value ("FILE_START: " + $relative)
    Add-Content -Path $OutputFile -Encoding UTF8 -Value ("FILE_NAME:  " + $item.Name)
    Add-Content -Path $OutputFile -Encoding UTF8 -Value ("FULL_PATH:  " + $file)
    Add-Content -Path $OutputFile -Encoding UTF8 -Value ("SIZE:       " + $item.Length + " bytes")
    Add-Content -Path $OutputFile -Encoding UTF8 -Value ("UPDATED_AT: " + $item.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss"))
    Add-Content -Path $OutputFile -Encoding UTF8 -Value ("-" * 120)
    Add-Content -Path $OutputFile -Encoding UTF8 -Value "CONTENT_BEGIN"

    try {
        $content = Get-Content -LiteralPath $file -Raw -Encoding UTF8
    }
    catch {
        $content = Get-Content -LiteralPath $file -Raw
    }

    Add-Content -Path $OutputFile -Encoding UTF8 -Value $content
    Add-Content -Path $OutputFile -Encoding UTF8 -Value ""
    Add-Content -Path $OutputFile -Encoding UTF8 -Value "CONTENT_END"
    Add-Content -Path $OutputFile -Encoding UTF8 -Value ("FILE_END: " + $relative)
    Add-Content -Path $OutputFile -Encoding UTF8 -Value ("=" * 120)

    $count++
}

# =========================================================
# 9) ДОПИСЫВАЕМ ПРОПУЩЕННЫЕ ФАЙЛЫ
# =========================================================
if ($MissingFiles.Count -gt 0) {
    Add-Content -Path $Manifest -Encoding UTF8 -Value ""
    Add-Content -Path $Manifest -Encoding UTF8 -Value "MISSING FILES:"
    foreach ($m in $MissingFiles) {
        Add-Content -Path $Manifest -Encoding UTF8 -Value ("MISSING: " + $m)
    }

    Add-Content -Path $OutputFile -Encoding UTF8 -Value ""
    Add-Content -Path $OutputFile -Encoding UTF8 -Value ("=" * 120)
    Add-Content -Path $OutputFile -Encoding UTF8 -Value "MISSING FILES"
    Add-Content -Path $OutputFile -Encoding UTF8 -Value ("-" * 120)
    foreach ($m in $MissingFiles) {
        Add-Content -Path $OutputFile -Encoding UTF8 -Value $m
    }
    Add-Content -Path $OutputFile -Encoding UTF8 -Value ("=" * 120)
}

# =========================================================
# 10) ВЫВОД В КОНСОЛЬ
# =========================================================
Write-Host ""
Write-Host "Готово." -ForegroundColor Green
Write-Host "Собрано файлов: $count"
Write-Host "Файл для отправки: $OutputFile"
Write-Host "Список файлов:     $Manifest"

if ($MissingFiles.Count -gt 0) {
    Write-Host ""
    Write-Host "Не найдены:" -ForegroundColor Yellow
    foreach ($m in $MissingFiles) {
        Write-Host " - $m" -ForegroundColor Yellow
    }
}

if ($DuplicateHits.Count -gt 0) {
    Write-Host ""
    Write-Host "Некоторые имена файлов найдены несколько раз." -ForegroundColor Yellow
    Write-Host "Подробности смотри в manifest.txt" -ForegroundColor Yellow
}

Write-Host ""