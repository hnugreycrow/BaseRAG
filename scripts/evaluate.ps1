param([string]$BaseUrl = 'http://127.0.0.1:8080')
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Use PowerShell 7+ for multipart uploads.' }
$root = Split-Path $PSScriptRoot -Parent
$bases = Invoke-RestMethod "$BaseUrl/api/knowledge-bases"
$kbId = $bases[0].id
$upload = Invoke-RestMethod "$BaseUrl/api/knowledge-bases/$kbId/documents" -Method Post -Form @{
    file = Get-Item -LiteralPath "$root/evaluation/datasets/employee-handbook.md"
}
$null = Invoke-RestMethod "$BaseUrl/api/knowledge-bases/$kbId/documents/$($upload.documentId)/chunks" -Method Post
$cases = Get-Content -LiteralPath "$root/evaluation/datasets/questions.json" -Raw -Encoding utf8 | ConvertFrom-Json
$results = foreach ($case in $cases) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $body = @{ question = $case.question } | ConvertTo-Json -Compress
        $answer = Invoke-RestMethod "$BaseUrl/api/questions" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
        $evidence = @($answer.sources | ForEach-Object { $_.content }) -join "`n"
        $matched = @($case.expectedEvidence | Where-Object { $evidence.Contains($_) }).Count
        [pscustomobject]@{ id=$case.id; question=$case.question; referenceAnswer=$case.referenceAnswer; answerable=$case.answerable; actual=$answer; expectedEvidenceMatched=$matched; expectedEvidenceCount=$case.expectedEvidence.Count; elapsedMs=$watch.ElapsedMilliseconds; error=$null; humanReview='PENDING' }
    } catch {
        [pscustomobject]@{ id=$case.id; question=$case.question; error=$_.Exception.Message; elapsedMs=$watch.ElapsedMilliseconds; humanReview='PENDING' }
    }
}
$directory = Join-Path $root '.artifacts'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$output = Join-Path $directory ("evaluation-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
@{ createdAt=(Get-Date).ToUniversalTime().ToString('o'); documentId=$upload.documentId; note='Real API responses; human grading required. Evidence string matches are not answer quality scores.'; results=@($results) } |
    ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $output -Encoding utf8
Write-Output "Saved results: $output"
