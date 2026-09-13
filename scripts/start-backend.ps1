param([string]$EnvFile = "$PSScriptRoot/../.env")
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $EnvFile)) { throw 'Copy .env.example to .env and configure services first.' }
foreach ($line in Get-Content -LiteralPath $EnvFile -Encoding utf8) {
    if ($line.Trim() -eq '' -or $line.TrimStart().StartsWith('#')) { continue }
    $parts = $line.Split('=', 2)
    if ($parts.Length -ne 2 -or $parts[0] -notmatch '^[A-Z][A-Z0-9_]*$') { throw 'Invalid .env line' }
    [Environment]::SetEnvironmentVariable($parts[0], $parts[1], 'Process')
}
Push-Location "$PSScriptRoot/../backend"
try { & ./mvnw.cmd spring-boot:run; exit $LASTEXITCODE }
finally { Pop-Location }

