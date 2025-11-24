# ============================================
# flatten-for-chatgpt.ps1
# Собирает все исходники в один текстовый файл
# ============================================

$root    = Get-Location
$outFile = Join-Path $root "project-sources.txt"

Write-Host "Root: $root"
Write-Host "Output: $outFile"

# Удаляем старый файл, если был
if (Test-Path $outFile) {
    Remove-Item -Force $outFile
}

# Какие файлы включаем
$patterns = @("*.java", "*.kt", "*.xml", "*.yml", "*.yaml", "*.properties", "*.html", "*.js", "*.css")

# Берём всё из src (main + test)
$files = Get-ChildItem -Path ".\src" -Recurse -File | Where-Object {
    $name = $_.Name
    foreach ($p in $patterns) {
        if ($name -like $p) { return $true }
    }
    return $false
} | Sort-Object FullName

Write-Host "Found $($files.Count) files to export..."

foreach ($f in $files) {
    $rel = $f.FullName.Replace($root, "").TrimStart("\","/")

    "===== FILE: $rel =====" | Out-File -FilePath $outFile -Encoding UTF8 -Append
    Get-Content $f.FullName | Out-File -FilePath $outFile -Encoding UTF8 -Append
    "" | Out-File -FilePath $outFile -Encoding UTF8 -Append
}

Write-Host "Done. Combined sources in: $outFile"
