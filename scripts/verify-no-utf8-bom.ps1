Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$excludedDirectories = @(
    '.git',
    'node_modules',
    'backend/build',
    'frontend/dist',
    'frontend/node_modules',
    '.gradle'
)
$extensions = @(
    '.java', '.js', '.vue', '.css', '.html', '.json', '.md', '.yml', '.yaml', '.properties', '.xml', '.sql', '.ps1'
)
$findings = New-Object System.Collections.Generic.List[string]

Get-ChildItem -Path $repoRoot -Recurse -File | ForEach-Object {
    $fullName = $_.FullName
    $relative = [System.IO.Path]::GetRelativePath($repoRoot, $fullName).Replace('\', '/')
    foreach ($excluded in $excludedDirectories) {
        if ($relative -eq $excluded -or $relative.StartsWith($excluded + '/')) {
            return
        }
    }
    if ($extensions -notcontains $_.Extension.ToLowerInvariant()) {
        return
    }

    $stream = [System.IO.File]::OpenRead($fullName)
    try {
        if ($stream.Length -lt 3) {
            return
        }
        $buffer = New-Object byte[] 3
        [void]$stream.Read($buffer, 0, 3)
        if ($buffer[0] -eq 0xEF -and $buffer[1] -eq 0xBB -and $buffer[2] -eq 0xBF) {
            $findings.Add($relative) | Out-Null
        }
    } finally {
        $stream.Dispose()
    }
}

if ($findings.Count -gt 0) {
    Write-Error ("UTF-8 BOM is not allowed in source files:`n" + ($findings -join "`n"))
}

Write-Host 'No UTF-8 BOM found in source files.'