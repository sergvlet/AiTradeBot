# tools/project-snapshot/make_report_package.ps1
# Генерирует ОДИН текстовый дамп по выбранному пакету/пути.
# Примеры:
#   .\tools\project-snapshot\make_report_package.ps1 -Package com.chicu.aitradebot
#   .\tools\project-snapshot\make_report_package.ps1 -Path src\main\java\com\chicu\aitradebot\strategy\windowscalping
#   .\tools\project-snapshot\make_report_package.ps1 -Package com.chicu.aitradebot.strategy -IncludeContent -MaxLines 250

param(
    [string]$Package = "",
    [string]$Path = "",
    [switch]$IncludeContent,
    [int]$MaxLines = 180
)

$ErrorActionPreference = "Stop"

$root = (Get-Location).Path
$outDir = Join-Path $root "project_report"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Write-Utf8($path, $text) {
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $text, $utf8NoBom)
}

function Normalize-RelPath($full) {
    return $full.Substring($root.Length).TrimStart("\","/")
}

function Should-Exclude($fullPath) {
    $exclude = @(
        "\.idea\",
        "\target\",
        "\node_modules\",
        "\.git\",
        "\build\",
        "\out\",
        "\.gradle\",
        "\.settings\",
        "\.classpath",
        "\.project"
    )
    foreach ($ex in $exclude) { if ($fullPath -like "*$ex*") { return $true } }
    return $false
}

function PackageToPath($pkg) {
    if ([string]::IsNullOrWhiteSpace($pkg)) { return "" }
    $p = $pkg.Trim().Replace(".", "\")
    # Обычно Java в src/main/java
    return "src\main\java\$p"
}

# --- Определяем целевой путь ---
$targetRel = ""
if (-not [string]::IsNullOrWhiteSpace($Path)) {
    $targetRel = $Path.Trim().TrimStart("\","/")
} elseif (-not [string]::IsNullOrWhiteSpace($Package)) {
    $targetRel = PackageToPath $Package
} else {
    throw "Нужно указать -Package или -Path"
}

$targetAbs = Join-Path $root $targetRel
if (-not (Test-Path $targetAbs)) {
    throw "Целевой путь не найден: $targetRel (abs: $targetAbs). Проверь пакет/путь."
}

# --- Сбор файлов только в выбранной области ---
$allFiles = Get-ChildItem -Path $targetAbs -Recurse -File |
        Where-Object { -not (Should-Exclude $_.FullName) }

$javaFiles = $allFiles | Where-Object { $_.Extension -in @(".java", ".kt") }

# =========================
# 1) Дерево выбранной области
# =========================
$tree = $allFiles |
        ForEach-Object { Normalize-RelPath $_.FullName } |
        Sort-Object

$treeTxt = ($tree -join "`r`n")

# =========================
# 2) Spring аннотации
# =========================
$anno = @(
    "@SpringBootApplication",
    "@RestController","@Controller",
    "@Service","@Component","@Repository",
    "@Configuration",
    "@Bean",
    "@Scheduled"
)

$annoOut = New-Object System.Collections.Generic.List[string]
$annoOut.Add("file;annotation;line")

foreach ($f in $javaFiles) {
    $lines = Get-Content -LiteralPath $f.FullName
    for ($i=0; $i -lt $lines.Length; $i++) {
        foreach ($a in $anno) {
            if ($lines[$i].Contains($a)) {
                $rel = Normalize-RelPath $f.FullName
                $annoOut.Add("$rel;$a;"+($i+1))
            }
        }
    }
}
$annoCsv = ($annoOut -join "`r`n")

# =========================
# 3) Endpoints
# =========================
$epOut = New-Object System.Collections.Generic.List[string]
$epOut.Add("file;line;mapping")

$epRegex = "(?m)^\s*@(?:RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\b.*$"
foreach ($f in $javaFiles) {
    $lines = Get-Content -LiteralPath $f.FullName
    for ($i=0; $i -lt $lines.Length; $i++) {
        if ($lines[$i] -match $epRegex) {
            $rel = Normalize-RelPath $f.FullName
            $epOut.Add("$rel;"+($i+1)+";"""+($lines[$i].Trim())+"""")
        }
    }
}
$epCsv = ($epOut -join "`r`n")

# =========================
# 4) Классы + public/protected методы
# =========================
$rows = New-Object System.Collections.Generic.List[string]
$rows.Add("file;package;class;method_signature")

foreach ($f in $javaFiles) {
    $content = Get-Content -Raw -LiteralPath $f.FullName

    $pkg = ""
    $mPkg = [regex]::Match($content, "(?m)^\s*package\s+([a-zA-Z0-9\._]+)\s*;")
    if ($mPkg.Success) { $pkg = $mPkg.Groups[1].Value }

    $classMatches = [regex]::Matches($content, "(?m)^\s*(public\s+)?(class|interface|enum|record)\s+([A-Za-z0-9_]+)")
    foreach ($cm in $classMatches) {
        $cls = $cm.Groups[3].Value

        $methodMatches = [regex]::Matches(
                $content,
                "(?m)^\s*(public|protected)\s+([A-Za-z0-9_<>,\[\]\.?]+)\s+([A-Za-z0-9_]+)\s*\(([^\)]*)\)\s*(throws\s+[A-Za-z0-9_,\s]+)?\s*\{?"
        )

        foreach ($mm in $methodMatches) {
            $sig = ($mm.Value -replace "\s+", " ").Trim()
            $rel = Normalize-RelPath $f.FullName
            $rows.Add("$rel;$pkg;$cls;""$sig""")
        }
    }
}
$classesMethodsCsv = ($rows -join "`r`n")

# =========================
# 5) (Опционально) первые N строк исходников
# =========================
$contentOut = ""
if ($IncludeContent) {
    $snips = New-Object System.Collections.Generic.List[string]
    $snips.Add("file;first_lines")
    foreach ($f in $javaFiles) {
        $rel = Normalize-RelPath $f.FullName
        $lines = Get-Content -LiteralPath $f.FullName
        $take = [Math]::Min($MaxLines, $lines.Length)
        $head = ($lines[0..($take-1)] -join "`n")
        $snips.Add("----- FILE: $rel -----")
        $snips.Add($head)
        $snips.Add("")
    }
    $contentOut = ($snips -join "`r`n")
}

# =========================
# 6) ОДИН итоговый файл
# =========================
$baseName = ""
if (-not [string]::IsNullOrWhiteSpace($Package)) {
    $baseName = $Package.Trim()
} else {
    $baseName = $targetRel.Trim().Replace("\","_").Replace("/","_")
}
$baseNameSafe = ($baseName -replace "[^A-Za-z0-9\._-]", "_")
$outPath = Join-Path $outDir ("CHAT_DUMP_" + $baseNameSafe + ".txt")

$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("===== PROJECT ROOT =====")
[void]$sb.AppendLine($root)
[void]$sb.AppendLine("")

[void]$sb.AppendLine("===== TARGET =====")
[void]$sb.AppendLine("rel: " + $targetRel)
[void]$sb.AppendLine("abs: " + $targetAbs)
[void]$sb.AppendLine("")

[void]$sb.AppendLine("===== TREE (TARGET) =====")
[void]$sb.AppendLine($treeTxt)
[void]$sb.AppendLine("")

[void]$sb.AppendLine("===== SPRING ANNOTATIONS (file;annotation;line) =====")
[void]$sb.AppendLine($annoCsv)
[void]$sb.AppendLine("")

[void]$sb.AppendLine("===== ENDPOINTS (file;line;mapping) =====")
[void]$sb.AppendLine($epCsv)
[void]$sb.AppendLine("")

[void]$sb.AppendLine("===== CLASSES+METHODS (CSV: file;package;class;method_signature) =====")
[void]$sb.AppendLine($classesMethodsCsv)
[void]$sb.AppendLine("")

if ($IncludeContent) {
    [void]$sb.AppendLine("===== FILE CONTENT (HEAD, MaxLines=$MaxLines) =====")
    [void]$sb.AppendLine($contentOut)
}

Write-Utf8 $outPath $sb.ToString()

Write-Host "✅ DONE: $outPath"
