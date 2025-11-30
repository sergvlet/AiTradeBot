param(
    [Parameter(Mandatory = $true)]
    [string]$FolderPath,
    [string]$OutputFileName = "resources-dump.txt"
)

# === Абсолютно независимый скрипт ===
# Корень проекта = текущая рабочая папка
$root = Get-Location

# Нормализация пути
if (-not [System.IO.Path]::IsPathRooted($FolderPath)) {
    $scanPath = Join-Path $root $FolderPath
} else {
    $scanPath = $FolderPath
}

# Проверяем существование
if (-not (Test-Path $scanPath)) {
    Write-Host "❌ Папка не найдена: $scanPath" -ForegroundColor Red
    exit 1
}

# Итоговый файл — В КОРНЕ проекта
$outFile = Join-Path $root $OutputFileName

Write-Host "📁 Сканирую папку: $scanPath" -ForegroundColor Cyan
Write-Host "📝 Сохраняю в файл: $outFile" -ForegroundColor Cyan

# Очищаем файл
"" | Set-Content -Path $outFile -Encoding UTF8

# Фильтр — только HTML / CSS / JS
$patterns = "*.html", "*.htm", "*.css", "*.js"

$files = Get-ChildItem -Path $scanPath -Recurse -Include $patterns -File

if (-not $files -or $files.Count -eq 0) {
    Write-Host "⚠️ Не найдено html/css/js файлов." -ForegroundColor Yellow
    exit 0
}

# Заголовок
Add-Content -Path $outFile -Value "==========================================="
Add-Content -Path $outFile -Value "📄 Найдены HTML / CSS / JS файлы"
Add-Content -Path $outFile -Value "Сканирование: $scanPath"
Add-Content -Path $outFile -Value "==========================================="
Add-Content -Path $outFile -Value ""

foreach ($f in $files) {
    Add-Content -Path $outFile -Value $f.FullName
}

Add-Content -Path $outFile -Value ""
Add-Content -Path $outFile -Value "==========================================="
Add-Content -Path $outFile -Value "📦 Содержимое файлов"
Add-Content -Path $outFile -Value "==========================================="
Add-Content -Path $outFile -Value ""

# Вывод содержимого
foreach ($f in $files) {
    Add-Content -Path $outFile -Value "-------------------------------------------"
    Add-Content -Path $outFile -Value "📌 FILE: $($f.FullName)"
    Add-Content -Path $outFile -Value "-------------------------------------------"
    Add-Content -Path $outFile -Value ""

    Get-Content $f.FullName -Encoding UTF8 | Add-Content -Path $outFile -Encoding UTF8

    Add-Content -Path $outFile -Value ""
    Add-Content -Path $outFile -Value ""
}

Write-Host ""
Write-Host "✅ ГОТОВО! Файл создан: $outFile" -ForegroundColor Green
