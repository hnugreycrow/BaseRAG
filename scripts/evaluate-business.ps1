param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidateSet('development', 'test', 'all')]
    [string]$Split = 'development',
    [string]$EmbeddingModelId,
    [string]$KnowledgeBaseId,
    [switch]$KeepKnowledgeBase,
    [ValidateRange(0, 1000)]
    [int]$Limit = 0,
    [ValidateRange(10, 600)]
    [int]$TimeoutSec = 180,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw '请使用 PowerShell 7 或更高版本运行批量上传评测。'
}

$root = Split-Path $PSScriptRoot -Parent
$datasetDirectory = Join-Path $root 'evaluation/datasets/business-simulation'
$questionsPath = Join-Path $datasetDirectory 'questions.json'
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $root '.artifacts'
}

& (Join-Path $PSScriptRoot 'validate-business-dataset.ps1') -DatasetDirectory $datasetDirectory | Out-Null
$dataset = Get-Content -LiteralPath $questionsPath -Raw -Encoding utf8 | ConvertFrom-Json
$cases = @($dataset.cases | Where-Object { $Split -eq 'all' -or $_.split -eq $Split })
if ($Limit -gt 0) { $cases = @($cases | Select-Object -First $Limit) }
if ($cases.Count -eq 0) { throw "分组 '$Split' 没有可执行的评测题。" }
$corpusDocuments = @(Get-ChildItem -LiteralPath $datasetDirectory -File |
    Where-Object { $_.Name -match '^\d{2}-.*\.md$' } |
    Sort-Object Name)

function Invoke-JsonApi {
    param(
        [Parameter(Mandatory)] [string]$Uri,
        [Parameter(Mandatory)] [ValidateSet('Get', 'Post', 'Patch')] [string]$Method,
        [object]$Body
    )

    $parameters = @{
        Uri = $Uri
        Method = $Method
        TimeoutSec = $TimeoutSec
    }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 20 -Compress
        $parameters.ContentType = 'application/json; charset=utf-8'
        $parameters.Body = [Text.Encoding]::UTF8.GetBytes($json)
    }
    $response = Invoke-RestMethod @parameters
    if ($response.code -ne 'SUCCESS') {
        throw "API 返回失败：$($response.code) $($response.message)"
    }
    return $response
}

function Get-HttpFailure {
    param([Management.Automation.ErrorRecord]$Record, [string]$Stage)

    $statusCode = $null
    $responseBody = $null
    $requestId = $null
    $code = $null
    $message = $Record.Exception.Message
    $response = $Record.Exception.Response
    if ($null -ne $response) {
        try { $statusCode = [int]$response.StatusCode } catch { }
        try { $responseBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() } catch { }
        if (-not [string]::IsNullOrWhiteSpace($responseBody)) {
            try {
                $parsed = $responseBody | ConvertFrom-Json
                $requestId = $parsed.requestId
                $code = $parsed.code
                if ($parsed.message) { $message = $parsed.message }
            } catch { }
        }
    }
    return [pscustomobject]@{
        stage = $Stage
        statusCode = $statusCode
        code = $code
        message = $message
        requestId = $requestId
        responseBody = $responseBody
    }
}

function Get-Percentile {
    param([long[]]$Values, [double]$Percentile)
    if ($Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Ceiling($Percentile * $sorted.Count) - 1)
    return $sorted[$index]
}

function Get-CombinedCorpusHash {
    param([IO.FileInfo[]]$Files)
    $entries = foreach ($file in $Files) {
        "$($file.Name):$((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash)"
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($entries -join "`n"))
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$outputPath = Join-Path $OutputDirectory "business-evaluation-$Split-$runId.json"
$createdKnowledgeBase = $false
$knowledgeBase = $null
$activeStage = 'initialize'
$runFailure = $null
$cleanupFailure = $null
$documentImports = [Collections.Generic.List[object]]::new()
$results = [Collections.Generic.List[object]]::new()
$runStarted = [Diagnostics.Stopwatch]::StartNew()
$gitCommit = (& git -C $root rev-parse HEAD).Trim()
$gitDirty = @(& git -C $root status --porcelain).Count -gt 0
$corpusHash = Get-CombinedCorpusHash -Files $corpusDocuments
$questionsHash = (Get-FileHash -LiteralPath $questionsPath -Algorithm SHA256).Hash.ToLowerInvariant()
$ragConfig = $null

try {
    $activeStage = 'load_evaluation_config'
    $configEnvelope = Invoke-JsonApi -Uri "$BaseUrl/api/evaluation/config" -Method Get
    $ragConfig = $configEnvelope.data
    if ([string]::IsNullOrWhiteSpace($KnowledgeBaseId)) {
        $activeStage = 'create_knowledge_base'
        $knowledgeBaseName = "RAG评测-$Split-$runId"
        if ([string]::IsNullOrWhiteSpace($EmbeddingModelId)) {
            $models = Invoke-JsonApi -Uri "$BaseUrl/api/knowledge-bases/embedding-models" -Method Get
            $defaultModel = @($models.data | Where-Object { $_.defaultModel }) | Select-Object -First 1
            if ($null -eq $defaultModel) { throw '后端没有配置默认 Embedding 模型。' }
            $EmbeddingModelId = [string]$defaultModel.id
        }
        $createBody = [ordered]@{ name = $knowledgeBaseName }
        $createBody.embeddingModelId = $EmbeddingModelId
        $created = Invoke-JsonApi -Uri "$BaseUrl/api/knowledge-bases" -Method Post -Body $createBody
        $knowledgeBase = $created.data
        $KnowledgeBaseId = [string]$knowledgeBase.id
        $createdKnowledgeBase = $true

        foreach ($document in $corpusDocuments) {
            $activeStage = "upload:$($document.Name)"
            $watch = [Diagnostics.Stopwatch]::StartNew()
            $uploadParameters = @{
                Uri = "$BaseUrl/api/knowledge-bases/$KnowledgeBaseId/documents"
                Method = 'Post'
                Form = @{ file = $document }
                TimeoutSec = $TimeoutSec
            }
            $uploadedEnvelope = Invoke-RestMethod @uploadParameters
            if ($uploadedEnvelope.code -ne 'SUCCESS') {
                throw "上传 $($document.Name) 失败：$($uploadedEnvelope.code) $($uploadedEnvelope.message)"
            }
            $uploaded = $uploadedEnvelope.data

            $activeStage = "chunk:$($document.Name)"
            $chunkUri = "$BaseUrl/api/knowledge-bases/$KnowledgeBaseId/documents/$($uploaded.documentId)/chunks"
            $chunkedEnvelope = Invoke-JsonApi -Uri $chunkUri -Method Post
            $chunked = $chunkedEnvelope.data
            $documentImports.Add([pscustomobject]@{
                file = $document.Name
                documentId = $chunked.documentId
                status = $chunked.status
                chunkCount = $chunked.chunkCount
                elapsedMs = $watch.ElapsedMilliseconds
                uploadRequestId = $uploadedEnvelope.requestId
                chunkRequestId = $chunkedEnvelope.requestId
            })
        }
    } else {
        $activeStage = 'load_knowledge_base'
        $existing = Invoke-JsonApi -Uri "$BaseUrl/api/knowledge-bases/$KnowledgeBaseId" -Method Get
        $knowledgeBase = $existing.data
        $documentsUri = "$BaseUrl/api/knowledge-bases/$KnowledgeBaseId/documents?page=1&pageSize=100"
        $documentsEnvelope = Invoke-JsonApi -Uri $documentsUri -Method Get
        $existingDocuments = @($documentsEnvelope.data.items)
        $missingDocuments = @($corpusDocuments | Where-Object {
            $_.Name -notin $existingDocuments.name
        })
        $notReadyDocuments = @($existingDocuments | Where-Object {
            $_.name -in $corpusDocuments.Name -and $_.status -ne 'READY'
        })
        if ($missingDocuments.Count -gt 0) {
            throw "复用知识库缺少文档：$($missingDocuments.Name -join ', ')"
        }
        if ($notReadyDocuments.Count -gt 0) {
            throw "复用知识库存在未就绪文档：$($notReadyDocuments.name -join ', ')"
        }
    }

    foreach ($case in $cases) {
        $activeStage = "question:$($case.id)"
        $watch = [Diagnostics.Stopwatch]::StartNew()
        try {
            $questionBody = @{ question = $case.question; knowledgeBaseIds = @($KnowledgeBaseId) }
            $envelope = Invoke-JsonApi -Uri "$BaseUrl/api/questions" -Method Post -Body $questionBody
            $answer = $envelope.data
            $sources = @($answer.sources)
            $evidenceMatches = foreach ($evidence in @($case.requiredEvidence)) {
                $matched = @($sources | Where-Object {
                    $_.documentName -eq $evidence.sourceFile -and
                    ([string]$_.content).Contains([string]$evidence.text, [StringComparison]::Ordinal)
                })
                [pscustomobject]@{
                    sourceFile = $evidence.sourceFile
                    heading = $evidence.heading
                    text = $evidence.text
                    matched = $matched.Count -gt 0
                    citationIds = @($matched.citationId)
                    matchedHeadings = @($matched.heading)
                }
            }
            $scopeViolations = @($sources | Where-Object {
                [string]$_.knowledgeBaseId -ne $KnowledgeBaseId
            })
            $completeEvidence = if ($case.answerable) {
                @($evidenceMatches | Where-Object { -not $_.matched }).Count -eq 0
            } else { $null }

            $results.Add([pscustomobject]@{
                id = $case.id
                split = $case.split
                type = $case.type
                tags = @($case.tags)
                question = $case.question
                answerable = $case.answerable
                referenceAnswer = $case.referenceAnswer
                requiredEvidence = @($case.requiredEvidence)
                evidenceMatches = @($evidenceMatches)
                completeEvidence = $completeEvidence
                elapsedMs = $watch.ElapsedMilliseconds
                requestId = $envelope.requestId
                sourceCount = $sources.Count
                scopeViolationCount = $scopeViolations.Count
                actual = $answer
                error = $null
                humanReview = [pscustomobject]@{
                    answerCorrect = 'PENDING'
                    citationsSupported = 'PENDING'
                    refusalCorrect = 'PENDING'
                    notes = ''
                }
            })
        } catch {
            $results.Add([pscustomobject]@{
                id = $case.id
                split = $case.split
                type = $case.type
                tags = @($case.tags)
                question = $case.question
                answerable = $case.answerable
                referenceAnswer = $case.referenceAnswer
                requiredEvidence = @($case.requiredEvidence)
                evidenceMatches = @()
                completeEvidence = if ($case.answerable) { $false } else { $null }
                elapsedMs = $watch.ElapsedMilliseconds
                requestId = $null
                sourceCount = 0
                scopeViolationCount = 0
                actual = $null
                error = Get-HttpFailure -Record $_ -Stage $activeStage
                humanReview = [pscustomobject]@{
                    answerCorrect = 'PENDING'
                    citationsSupported = 'PENDING'
                    refusalCorrect = 'PENDING'
                    notes = ''
                }
            })
        }
    }
} catch {
    $runFailure = Get-HttpFailure -Record $_ -Stage $activeStage
} finally {
    if ($createdKnowledgeBase -and -not $KeepKnowledgeBase -and $KnowledgeBaseId) {
        try {
            $deleteParameters = @{
                Uri = "$BaseUrl/api/knowledge-bases/$KnowledgeBaseId"
                Method = 'Delete'
                TimeoutSec = $TimeoutSec
            }
            Invoke-RestMethod @deleteParameters | Out-Null
        } catch {
            $cleanupFailure = Get-HttpFailure -Record $_ -Stage 'cleanup_knowledge_base'
        }
    }

    $successful = @($results | Where-Object { $null -eq $_.error })
    $answerableResults = @($results | Where-Object { $_.answerable })
    $allEvidenceMatches = @($results.evidenceMatches)
    $matchedEvidence = @($allEvidenceMatches | Where-Object { $_.matched }).Count
    $completeEvidenceCases = @($answerableResults | Where-Object { $_.completeEvidence }).Count
    $latencies = @($successful.elapsedMs | ForEach-Object { [long]$_ })
    $summary = [pscustomobject]@{
        selectedCases = $cases.Count
        completedCases = $results.Count
        successfulCases = $successful.Count
        failedCases = @($results | Where-Object { $null -ne $_.error }).Count
        answerableCases = $answerableResults.Count
        unanswerableCases = @($results | Where-Object { -not $_.answerable }).Count
        requiredEvidenceItems = @($cases.requiredEvidence).Count
        matchedEvidenceItems = $matchedEvidence
        evidenceItemRecall = if (@($cases.requiredEvidence).Count -gt 0) {
            [Math]::Round($matchedEvidence / @($cases.requiredEvidence).Count, 4)
        } else { $null }
        completeEvidenceCases = $completeEvidenceCases
        completeEvidenceRate = if ($answerableResults.Count -gt 0) {
            [Math]::Round($completeEvidenceCases / $answerableResults.Count, 4)
        } else { $null }
        scopeViolations = ($results | Measure-Object scopeViolationCount -Sum).Sum
        latencyMs = [pscustomobject]@{
            p50 = Get-Percentile -Values $latencies -Percentile 0.50
            p95 = Get-Percentile -Values $latencies -Percentile 0.95
            max = if ($latencies.Count -gt 0) { ($latencies | Measure-Object -Maximum).Maximum } else { $null }
        }
        humanReviewPending = $results.Count
    }

    $artifact = [ordered]@{
        schemaVersion = '1.0'
        createdAt = (Get-Date).ToUniversalTime().ToString('o')
        corpusId = $dataset.corpusId
        corpusVersion = $dataset.corpusVersion
        corpusSha256 = $corpusHash
        questionsSha256 = $questionsHash
        git = [pscustomobject]@{
            commit = $gitCommit
            dirty = $gitDirty
        }
        split = $Split
        limit = $Limit
        baseUrl = $BaseUrl
        knowledgeBase = $knowledgeBase
        knowledgeBaseId = $KnowledgeBaseId
        knowledgeBaseCreatedForRun = $createdKnowledgeBase
        knowledgeBaseKept = [bool]($createdKnowledgeBase -and $KeepKnowledgeBase)
        ragConfig = $ragConfig
        documentImports = @($documentImports)
        runElapsedMs = $runStarted.ElapsedMilliseconds
        runFailure = $runFailure
        cleanupFailure = $cleanupFailure
        summary = $summary
        results = @($results)
    }
    $artifact | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $outputPath -Encoding utf8
}

Write-Output "评测结果：$outputPath"
Write-Output "成功请求：$($summary.successfulCases)/$($summary.selectedCases)"
Write-Output "必要证据召回：$($summary.matchedEvidenceItems)/$($summary.requiredEvidenceItems)"
Write-Output "完整证据题：$($summary.completeEvidenceCases)/$($summary.answerableCases)"
if ($createdKnowledgeBase -and $KeepKnowledgeBase) {
    Write-Output "保留知识库：$KnowledgeBaseId"
}
if ($null -ne $runFailure) {
    throw "评测在阶段 '$($runFailure.stage)' 失败，详情已写入结果文件。"
}
if ($summary.failedCases -gt 0) {
    throw "评测有 $($summary.failedCases) 个请求失败，详情已写入结果文件。"
}
if ($summary.scopeViolations -gt 0) {
    throw "评测发现 $($summary.scopeViolations) 个跨知识库来源，详情已写入结果文件。"
}
