param(
    [string]$ApiKey = "",
    [string]$HostAddress = "100.74.130.118",
    [int]$Port = 8765,
    [switch]$Recreate
)

$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Fail {
    param([string]$Message)
    Write-Host ""
    Write-Host "ERROR: $Message" -ForegroundColor Red
    exit 1
}

function Get-PythonCandidate {
    $candidates = @(
        @{ Command = "py"; Args = @("-3.11") },
        @{ Command = "py"; Args = @("-3.10") },
        @{ Command = "python"; Args = @() }
    )

    foreach ($candidate in $candidates) {
        try {
            $versionOutput = & $candidate.Command @($candidate.Args + @("-c", "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}|{sys.executable}|{8 * tuple.__itemsize__}')")) 2>$null
            if ($LASTEXITCODE -eq 0 -and $versionOutput) {
                $parts = $versionOutput.Trim().Split("|")
                return [pscustomobject]@{
                    Command = $candidate.Command
                    Args = $candidate.Args
                    Version = $parts[0]
                    Executable = $parts[1]
                    Bits = $parts[2]
                }
            }
        } catch {
            continue
        }
    }

    return $null
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

Write-Step "Checking Python"
$python = Get-PythonCandidate
if ($null -eq $python) {
    Fail "Python was not found. Install 64-bit Python 3.10 or 3.11 from python.org, then open a new PowerShell."
}

$versionParts = $python.Version.Split(".")
$major = [int]$versionParts[0]
$minor = [int]$versionParts[1]
if ($major -ne 3 -or ($minor -ne 10 -and $minor -ne 11)) {
    Fail "Unsupported Python $($python.Version). Use 64-bit Python 3.10 or 3.11 for PaddleOCR 2.x on Windows."
}
if ($python.Bits -ne "64") {
    Fail "Unsupported $($python.Bits)-bit Python. Install 64-bit Python 3.10 or 3.11."
}

Write-Host "Python: $($python.Version)"
Write-Host "Path:   $($python.Executable)"

$venvPath = Join-Path $scriptDir "ocr_env"
$venvPython = Join-Path $venvPath "Scripts\python.exe"
$venvActivate = Join-Path $venvPath "Scripts\Activate.ps1"

if ($Recreate -and (Test-Path $venvPath)) {
    Write-Step "Removing existing OCR virtual environment"
    Remove-Item -LiteralPath $venvPath -Recurse -Force
}

if (-not (Test-Path $venvPython)) {
    Write-Step "Creating OCR virtual environment"
    & $python.Command @($python.Args + @("-m", "venv", $venvPath))
}

if (-not (Test-Path $venvPython) -or -not (Test-Path $venvActivate)) {
    Fail "Virtual environment creation failed. Expected $venvActivate to exist."
}

Write-Step "Upgrading pip tooling"
& $venvPython -m pip install --upgrade pip setuptools wheel

Write-Step "Installing OCR Python dependencies"
& $venvPython -m pip install -r (Join-Path $scriptDir "requirements.txt")

Write-Step "Verifying installed package versions"
@'
import importlib.metadata as metadata
import sys

packages = ["paddlepaddle", "paddleocr", "fastapi", "uvicorn", "Pillow"]
print("python", sys.version.replace("\n", " "))
for package in packages:
    try:
        print(package, metadata.version(package))
    except metadata.PackageNotFoundError:
        print(package, "NOT_INSTALLED")
        raise
'@ | & $venvPython -

$envFile = Join-Path $scriptDir "ocr.env.ps1"
if (-not (Test-Path $envFile)) {
    Write-Step "Creating local OCR environment file"
    $effectiveApiKey = if ($ApiKey) { $ApiKey } else { "CHANGE_ME_TO_THE_SAME_VALUE_AS_LEDGER_OCR_API_KEY" }
    $content = @"
`$env:OCR_API_KEY="$effectiveApiKey"
`$env:OCR_HOST="$HostAddress"
`$env:OCR_PORT="$Port"

`$env:OCR_LANG="korean"
`$env:OCR_ROTATION_MODE="off"
`$env:OCR_DEVICE="cpu"
`$env:OCR_CPU_THREADS="4"

`$env:LLM_PROVIDER="ollama"
`$env:LLM_BASE_URL="http://127.0.0.1:11434"
`$env:LLM_MODEL="gemma2:2b"
`$env:LLM_TIMEOUT_SECONDS="90"
"@
    Set-Content -LiteralPath $envFile -Value $content -Encoding UTF8
} else {
    Write-Host "Keeping existing ocr.env.ps1"
}

Write-Step "Done"
Write-Host "Run the OCR server with:"
Write-Host "  Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force"
Write-Host "  .\ocr_env\Scripts\Activate.ps1"
Write-Host "  . .\ocr.env.ps1"
Write-Host "  python .\ocr_service.py"
