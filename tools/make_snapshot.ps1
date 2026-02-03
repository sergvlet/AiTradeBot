param(
    [string]$OutDir = ".\_snapshot"
)

$ErrorActionPreference = "Stop"
$PSDefaultParameterValues['Out-File:Encoding'] = 'utf8'
$PSDefaultParameterValues['Set-Content:Encoding'] = 'utf8'

$root = Get-Location

$include = @(
    "pom.xml",
    "src\main\java",
    "src\main\resources",
    "src\test\java",
    "src\test\resources"
)

$excludeDirs = @("\target\", "\.idea\", "\.git\", "\node_modules\", "\dist\", "\build\", "\.gradle\", "\out\")
$excludeExt  = @(".class", ".jar", ".log")

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$ts = Get-Date -Format "yyyyMMdd_HHmmss"
$zipName = "project_snapshot_$ts.zip"
$zipPath = Join-Path $OutDir $zipName

# ============================================================
# TREE (pure PowerShell, PS 5.1 safe)
# ============================================================
$treePath = Join-Path $OutDir ("TREE_{0}.txt" -f $ts)

function Should-IncludeFile {
    param([string]$fullPath)

    foreach ($d in $excludeDirs) {
        if ($fullPath -like "*$d*") { return $false }
    }
    foreach ($e in $excludeExt) {
        if ($fullPath.ToLower().EndsWith($e)) { return $false }
    }
    return $true
}

function Write-Tree {
    param(
        [string]$Path,
        [int]$Level = 0,
        [int]$MaxDepth = 25
    )

    if ($Level -gt $MaxDepth) { return }

    $indent = ("  " * $Level)

    # directories
    Get-ChildItem -LiteralPath $Path -Directory -Force -ErrorAction SilentlyContinue |
            Sort-Object Name |
            ForEach-Object {
                ("{0}[DIR] {1}" -f $indent, $_.Name) | Out-File -Append $treePath
                Write-Tree -Path $_.FullName -Level ($Level + 1) -MaxDepth $MaxDepth
            }

    # files
    Get-ChildItem -LiteralPath $Path -File -Force -ErrorAction SilentlyContinue |
            Sort-Object Name |
            ForEach-Object {
                ("{0}[FILE] {1}" -f $indent, $_.Name) | Out-File -Append $treePath
            }
}

("ROOT: {0}" -f $root.Path) | Out-File $treePath
"" | Out-File -Append $treePath

foreach ($p in $include) {
    $full = Join-Path $root $p
    if (!(Test-Path $full)) { continue }

    "" | Out-File -Append $treePath
    ("=== {0} ===" -f $p) | Out-File -Append $treePath

    if (Test-Path $full -PathType Leaf) {
        ("[FILE] {0}" -f (Split-Path $full -Leaf)) | Out-File -Append $treePath
    } else {
        Write-Tree -Path $full -Level 0 -MaxDepth 25
    }
}

# ============================================================
# FILE LIST + ZIP
# ============================================================
$filesPath = Join-Path $OutDir ("FILES_{0}.txt" -f $ts)
$allFiles = New-Object System.Collections.Generic.List[string]

foreach ($p in $include) {
    $full = Join-Path $root $p
    if (!(Test-Path $full)) { continue }

    if (Test-Path $full -PathType Leaf) {
        $allFiles.Add($full) | Out-Null
        continue
    }

    Get-ChildItem -Recurse -File -Force $full -ErrorAction SilentlyContinue |
            Where-Object { Should-IncludeFile $_.FullName } |
            ForEach-Object { $allFiles.Add($_.FullName) | Out-Null }
}

$allFiles = $allFiles | Sort-Object -Unique

$allFiles |
        ForEach-Object {
            $rel = $_.Substring($root.Path.Length).TrimStart('\','/')
            $rel
        } | Out-File $filesPath

if (Test-Path $zipPath) { Remove-Item $zipPath -Force }

# Добавляем в zip: исходники + TREE/FILES
Compress-Archive -Path ($allFiles + @($treePath, $filesPath)) -DestinationPath $zipPath

Write-Host "OK: Snapshot created -> $zipPath"
Write-Host "OK: Tree -> $treePath"
Write-Host "OK: Files -> $filesPath"
