param(
    [string]$DatasetDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'evaluation/datasets/business-simulation')
)

$ErrorActionPreference = 'Stop'
$questionsPath = Join-Path $DatasetDirectory 'questions.json'
if (-not (Test-Path -LiteralPath $questionsPath -PathType Leaf)) {
    throw "Questions file not found: $questionsPath"
}

$dataset = Get-Content -LiteralPath $questionsPath -Raw -Encoding utf8 | ConvertFrom-Json
if ($dataset.schemaVersion -ne '1.0') { throw "Unsupported schemaVersion: $($dataset.schemaVersion)" }
if (-not $dataset.cases -or $dataset.cases.Count -eq 0) { throw 'Dataset has no cases.' }

$ids = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$errors = [Collections.Generic.List[string]]::new()
$answerableCount = 0
$unanswerableCount = 0
$developmentCount = 0
$testCount = 0
$usedSourceFiles = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)

foreach ($case in $dataset.cases) {
    if (-not $ids.Add([string]$case.id)) { $errors.Add("Duplicate id: $($case.id)") }
    if ([string]::IsNullOrWhiteSpace($case.question)) { $errors.Add("$($case.id): empty question") }
    if ([string]::IsNullOrWhiteSpace($case.referenceAnswer)) { $errors.Add("$($case.id): empty referenceAnswer") }

    if ($case.split -eq 'development') { $developmentCount++ }
    elseif ($case.split -eq 'test') { $testCount++ }
    else { $errors.Add("$($case.id): invalid split '$($case.split)'") }

    $evidence = @($case.requiredEvidence)
    if ($case.answerable) {
        $answerableCount++
        if ($evidence.Count -eq 0) { $errors.Add("$($case.id): answerable case has no evidence") }
    } else {
        $unanswerableCount++
        if ($evidence.Count -ne 0) { $errors.Add("$($case.id): unanswerable case has evidence") }
    }

    foreach ($item in $evidence) {
        $null = $usedSourceFiles.Add([string]$item.sourceFile)
        $sourcePath = Join-Path $DatasetDirectory $item.sourceFile
        if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
            $errors.Add("$($case.id): source file not found '$($item.sourceFile)'")
            continue
        }
        $source = Get-Content -LiteralPath $sourcePath -Raw -Encoding utf8
        $headingPattern = '(?ms)^## ' + [regex]::Escape([string]$item.heading) + "\r?\n(?<body>.*?)(?=^## |\z)"
        $section = [regex]::Match($source, $headingPattern)
        if (-not $section.Success) {
            $errors.Add("$($case.id): heading '$($item.heading)' not found in '$($item.sourceFile)'")
        } elseif (-not $section.Groups['body'].Value.Contains([string]$item.text, [StringComparison]::Ordinal)) {
            $errors.Add("$($case.id): evidence text not found under heading '$($item.heading)' in '$($item.sourceFile)'")
        }
    }
}

$corpusFiles = Get-ChildItem -LiteralPath $DatasetDirectory -File |
    Where-Object { $_.Name -match '^\d{2}-.*\.md$' }
foreach ($file in $corpusFiles) {
    if (-not $usedSourceFiles.Contains($file.Name)) {
        $errors.Add("Corpus file has no gold evidence coverage: '$($file.Name)'")
    }
}

if ($errors.Count -gt 0) {
    $errors | ForEach-Object { Write-Error $_ }
    throw "Dataset validation failed with $($errors.Count) error(s)."
}

[pscustomobject]@{
    SchemaVersion = $dataset.schemaVersion
    CorpusId = $dataset.corpusId
    CorpusVersion = $dataset.corpusVersion
    Cases = $dataset.cases.Count
    Development = $developmentCount
    Test = $testCount
    Answerable = $answerableCount
    Unanswerable = $unanswerableCount
    EvidenceItems = @($dataset.cases.requiredEvidence).Count
} | Format-List
