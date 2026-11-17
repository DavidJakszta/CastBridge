param(
    [Parameter(Mandatory = $true)]
    [string]$DeviceIp,
    [string]$Password = '',
    [string]$User = 'rokudev'
)

$ErrorActionPreference = 'Stop'
$zip = Join-Path $PSScriptRoot 'CastBridgeChannel.zip'

if (-not (Test-Path $zip)) {
    & (Join-Path $PSScriptRoot 'build-channel.ps1')
}

$info = Invoke-WebRequest -Uri "http://${DeviceIp}:8060/query/device-info" -UseBasicParsing -TimeoutSec 5
if ($info.Content -notmatch '<developer-enabled>true</developer-enabled>') {
    throw "Developer mode is not enabled on $DeviceIp. Enable it on the TV first (Home x3, Up x2, Right, Left, Right, Left, Right)."
}

# The dev installer is protected with HTTP Digest auth (realm "rokudev"), not a form field.
# curl handles the nonce challenge for us; PowerShell's Invoke-WebRequest does not.
$response = & curl.exe -s -w "`nHTTP_STATUS:%{http_code}" --digest -u "${User}:${Password}" `
    -X POST `
    -F "mysubmit=Install" `
    -F "archive=@$zip" `
    "http://${DeviceIp}/plugin_install"

Write-Host $response

if ($response -match 'HTTP_STATUS:(2\d\d)') {
    Write-Host "Channel installed on $DeviceIp"
} elseif ($response -match 'HTTP_STATUS:401') {
    throw "The Roku rejected the developer password. Re-enter developer mode and set a new one."
} else {
    throw "Install failed. Check the TV is in developer mode and the zip is valid."
}
