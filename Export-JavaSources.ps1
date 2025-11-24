param(
    [Parameter(Mandatory = $true)]
    [string]$FolderPath,           # например: src\main\java\com\chicu\aitradebot\web\controller\api
    [string]$OutputFileName = "project-sources.txt"  # имя файла в корне проекта
)

# 📌 Корень проекта = текущая папка, где ты запускаешь скрипт
$root = Get-Location

# Если путь относительный — приклеиваем к корню
if (-not [System.IO.Path]::IsPathRooted($FolderPath)) {
    $scanPath = Join-Path $root $FolderPath
} else {
    $scanPath = $FolderPath
}

if (-not (Test-Path $scanPath)) {
    Write-Host "❌ Папка не найдена: $scanPath" -ForegroundColor Red
    exit 1
}

# 📄 Файл, который создадим в КОРНЕ проекта
$outFile = Join-Path $root $OutputFileName

Write-Host "📁 Сканирую: $scanPath" -ForegroundColor Cyan
Write-Host "📝 Результат запишу в: $outFile" -ForegroundColor Cyan

# Очищаем/создаём файл (UTF8)
"" | Set-Content -Path $outFile -Encoding UTF8

# Находим все .java файлы
$files = Get-ChildItem -Path $scanPath -Recurse -Filter "*.java"

if (-not $files -or $files.Count -eq 0) {
    Write-Host "⚠️ Java-файлы не найдены." -ForegroundColor Yellow
    exit 0
}

# === 1. Заголовок со списком файлов ===
Add-Content -Path $outFile -Value "==========================================="
Add-Content -Path $outFile -Value "📄 Найдены Java-файлы"
Add-Content -Path $outFile -Value "Корень сканирования: $scanPath"
Add-Content -Path $outFile -Value "==========================================="

foreach ($f in $files) {
    Add-Content -Path $outFile -Value $f.FullName
}

Add-Content -Path $outFile -Value ""
Add-Content -Path $outFile -Value "==========================================="
Add-Content -Path $outFile -Value "📦 Содержимое файлов"
Add-Content -Path $outFile -Value "==========================================="
Add-Content -Path $outFile -Value ""

# === 2. Содержимое каждого файла ===
foreach ($f in $files) {
    Add-Content -Path $outFile -Value "-------------------------------------------"
    Add-Content -Path $outFile -Value "📌 FILE: $($f.FullName)"
    Add-Content -Path $outFile -Value "-------------------------------------------"
    Add-Content -Path $outFile -Value ""

    # Вставляем текст файла
    Get-Content $f.FullName | Add-Content -Path $outFile -Encoding UTF8

    Add-Content -Path $outFile -Value ""
    Add-Content -Path $outFile -Value ""
}

Write-Host ""
Write-Host "✅ Готово! Всё содержимое сохранено в: $outFile" -ForegroundColor Green
