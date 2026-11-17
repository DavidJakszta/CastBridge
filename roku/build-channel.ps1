param(
    [string]$Output = (Join-Path $PSScriptRoot 'CastBridgeChannel.zip')
)

$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'CastBridgeChannel'

if (-not (Test-Path $source)) {
    throw "Channel source not found at $source"
}

if (Test-Path $Output) {
    Remove-Item $Output -Force
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

# Roku requires forward slashes inside the archive, so build the zip entry by entry instead of
# using Compress-Archive (which writes Windows backslashes).
$archive = [System.IO.Compression.ZipFile]::Open($Output, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    $root = (Resolve-Path $source).Path.TrimEnd('\')
    Get-ChildItem -Path $source -Recurse -File | ForEach-Object {
        $relative = $_.FullName.Substring($root.Length + 1).Replace('\', '/')
        $entry = $archive.CreateEntry($relative, [System.IO.Compression.CompressionLevel]::Optimal)
        $stream = $entry.Open()
        try {
            $input = [System.IO.File]::OpenRead($_.FullName)
            try { $input.CopyTo($stream) } finally { $input.Dispose() }
        } finally {
            $stream.Dispose()
        }
    }
} finally {
    $archive.Dispose()
}

$size = (Get-Item $Output).Length
Write-Host "Built $Output ($size bytes)"
