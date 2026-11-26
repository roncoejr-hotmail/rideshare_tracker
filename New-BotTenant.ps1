<#
.SYNOPSIS
    Create a bot tenant on the Transport Ledger API using the local operator key.
.DESCRIPTION
    Reads the operator key from transport-operator-key.txt (repo root, never
    printed), POSTs /api/admin/tenants, and prints the new tenant's one-time
    bot key. Save that key immediately -- it is never shown again.
.EXAMPLE
    .\New-BotTenant.ps1 -Name "Receipt Bot"
    .\New-BotTenant.ps1 -Name "Receipt Bot" -KeyLabel "prod-agent" -BaseUrl "http://localhost:8080"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateLength(1, 80)]
    [string]$Name,

    [ValidateLength(1, 40)]
    [string]$KeyLabel = 'agent',

    [string]$BaseUrl = 'https://transportation-history.miosys.org',

    [string]$OperatorKeyFile = (Join-Path $PSScriptRoot 'transport-operator-key.txt')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

if (-not (Test-Path $OperatorKeyFile)) {
    throw "Operator key file not found: $OperatorKeyFile"
}
$operatorKey = Get-Content $OperatorKeyFile | Where-Object { $_.Trim().Length -gt 0 } | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($operatorKey)) {
    throw "Operator key file is empty: $OperatorKeyFile"
}
$operatorKey = $operatorKey.Trim()

$body = @{ name = $Name; keyLabel = $KeyLabel } | ConvertTo-Json -Compress
try {
    $result = Invoke-RestMethod -Method Post -Uri ($BaseUrl.TrimEnd('/') + '/api/admin/tenants') `
        -Headers @{ Authorization = "Bearer $operatorKey" } `
        -ContentType 'application/json' -Body $body
} catch {
    $detail = $_.ErrorDetails.Message
    if ([string]::IsNullOrWhiteSpace($detail)) { $detail = $_.Exception.Message }
    throw "Tenant creation failed: $detail"
} finally {
    $operatorKey = $null
}

Write-Host ''
Write-Host 'Tenant created. SAVE THE BOT KEY NOW -- it is never shown again:' -ForegroundColor Yellow
Write-Host ("  tenantId : {0}" -f $result.tenantId)
Write-Host ("  name     : {0}" -f $result.name)
Write-Host ("  keyId    : {0}" -f $result.keyId)
Write-Host ("  keyLabel : {0}" -f $result.keyLabel)
Write-Host ("  apiKey   : {0}" -f $result.apiKey)
