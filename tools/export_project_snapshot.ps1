# tools/project-snapshot/make_report.ps1
# Генерирует понятный отчёт по проекту: дерево, список классов, публичные методы, spring-аннотации, endpoints.

$ErrorActionPreference = "Stop"

$root = (Get-Location).Path
$outDir = Join-Path $root "project_report"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Write-Utf8($path, $text) {
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $text, $utf8NoBom)
}

# 1) Дерево проекта (без мусора)
$exclude = @("\.idea\", "\target\", "\node_modules\", "\.git\", "\build\", "\out\")
$allFiles = Get-ChildItem -Path $root -Recurse -File |
        Where-Object {
            $p = $_.FullName
            foreach ($ex in $exclude) { if ($p -like "*$ex*") { return $false } }
            return $true
        }

$tree = $allFiles |
        ForEach-Object { $_.FullName.Substring($root.Length).TrimStart("\") } |
        Sort-Object

Write-Utf8 (Join-Path $outDir "tree.txt") ($tree -join "`r`n")

# 2) Java/Kotlin классы + public/protected методы (быстрый парсер по regex)
$javaFiles = $allFiles | Where-Object { $_.Extension -in @(".java", ".kt") }

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

        $methodMatches = [regex]::Matches($content,
                "(?m)^\s*(public|protected)\s+([A-Za-z0-9_<>,\[\]\.?]+)\s+([A-Za-z0-9_]+)\s*\(([^\)]*)\)\s*(throws\s+[A-Za-z0-9_,\s]+)?\s*\{?")
        foreach ($mm in $methodMatches) {
            $sig = ($mm.Value -replace "\s+", " ").Trim()
            $rel = $f.FullName.Substring($root.Length).TrimStart("\")
            $rows.Add("$rel;$pkg;$cls;""$sig""")
        }
    }
}

Write-Utf8 (Join-Path $outDir "classes_methods.csv") ($rows -join "`r`n")

# 3) Spring аннотации (где сервисы/контроллеры/компоненты)
$anno = @("@RestController","@Controller","@Service","@Component","@Repository","@Configuration")
$annoOut = New-Object System.Collections.Generic.List[string]
$annoOut.Add("file;annotation;line")

foreach ($f in $javaFiles) {
    $lines = Get-Content -LiteralPath $f.FullName
    for ($i=0; $i -lt $lines.Length; $i++) {
        foreach ($a in $anno) {
            if ($lines[$i].Contains($a)) {
                $rel = $f.FullName.Substring($root.Length).TrimStart("\")
                $annoOut.Add("$rel;$a;"+($i+1))
            }
        }
    }
}
Write-Utf8 (Join-Path $outDir "spring_annotations.csv") ($annoOut -join "`r`n")

# 4) Endpoints (RequestMapping/Get/Post/Put/Delete)
$epOut = New-Object System.Collections.Generic.List[string]
$epOut.Add("file;line;mapping")

$epRegex = "(?m)^\s*@(?:RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping)\b.*$"
foreach ($f in $javaFiles) {
    $lines = Get-Content -LiteralPath $f.FullName
    for ($i=0; $i -lt $lines.Length; $i++) {
        if ($lines[$i] -match $epRegex) {
            $rel = $f.FullName.Substring($root.Length).TrimStart("\")
            $epOut.Add("$rel;"+($i+1)+";"""+($lines[$i].Trim())+"""")
        }
    }
}
Write-Utf8 (Join-Path $outDir "endpoints.csv") ($epOut -join "`r`n")

# 5) Упаковка отчёта
$zipPath = Join-Path $root "project_report.zip"
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
Compress-Archive -Path (Join-Path $outDir "*") -DestinationPath $zipPath

Write-Host "✅ DONE: $outDir"
Write-Host "✅ ZIP : $zipPath"
