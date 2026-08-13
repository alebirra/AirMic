# AirMic Release Build Script
# Generates both Self-Contained (Plug & Play) and Framework-Dependent (Lightweight) releases.

param(
    [string]$Version = "1.0.0",
    [switch]$SkipZip = $false
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = Join-Path $ScriptDir "windows\src\AirMic"
$CsprojPath = Join-Path $ProjectDir "AirMic.csproj"
$ReleaseDir = Join-Path $ScriptDir "release"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   AirMic Release Builder v$Version" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 1. Clean release directory
if (Test-Path $ReleaseDir) {
    Write-Host "[1/5] Cleaning existing release folder..." -ForegroundColor Yellow
    Remove-Item -Path $ReleaseDir -Recurse -Force
}
New-Item -ItemType Directory -Path $ReleaseDir -Force | Out-Null

$SelfContainedDir = Join-Path $ReleaseDir "AirMic-windows-x64-selfcontained"
$LightweightDir = Join-Path $ReleaseDir "AirMic-windows-x64-lightweight"

# 2. Build Self-Contained (Plug & Play)
Write-Host "[2/5] Building Self-Contained Release (Plug & Play, no .NET required)..." -ForegroundColor Green
dotnet publish $CsprojPath `
    -c Release `
    -r win-x64 `
    --self-contained true `
    -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true `
    -p:EnableCompressionInSingleFile=true `
    -p:Version=$Version `
    -o $SelfContainedDir

# 3. Build Framework-Dependent (Lightweight)
Write-Host "[3/5] Building Framework-Dependent Release (Lightweight)..." -ForegroundColor Green
dotnet publish $CsprojPath `
    -c Release `
    -r win-x64 `
    --self-contained false `
    -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true `
    -p:Version=$Version `
    -o $LightweightDir

# Copy documentation and assets
$ReadmePath = Join-Path $ScriptDir "README.md"
if (Test-Path $ReadmePath) {
    Copy-Item $ReadmePath -Destination $SelfContainedDir
    Copy-Item $ReadmePath -Destination $LightweightDir
}

# Remove PDB debug symbols from final packages
Get-ChildItem -Path $ReleaseDir -Filter "*.pdb" -Recurse | Remove-Item -Force

# 4. Create ZIP packages
if (-not $SkipZip) {
    Write-Host "[4/5] Packaging ZIP archives..." -ForegroundColor Green
    
    $SelfContainedZip = Join-Path $ReleaseDir "AirMic-v$Version-windows-x64-selfcontained.zip"
    $LightweightZip = Join-Path $ReleaseDir "AirMic-v$Version-windows-x64-lightweight.zip"

    Compress-Archive -Path "$SelfContainedDir\*" -DestinationPath $SelfContainedZip -Force
    Compress-Archive -Path "$LightweightDir\*" -DestinationPath $LightweightZip -Force
}

# 5. Generate Checksums
Write-Host "[5/5] Generating SHA-256 Checksums..." -ForegroundColor Green
$ChecksumFile = Join-Path $ReleaseDir "SHA256SUMS.txt"
$AllFiles = Get-ChildItem -Path $ReleaseDir -File -Recurse | Where-Object { $_.Name -ne "SHA256SUMS.txt" }

$ChecksumLines = foreach ($file in $AllFiles) {
    $hash = (Get-FileHash -Path $file.FullName -Algorithm SHA256).Hash.ToLower()
    $relPath = $file.FullName.Substring($ReleaseDir.Length + 1).Replace("\", "/")
    "$hash  $relPath"
}
$ChecksumLines | Out-File -FilePath $ChecksumFile -Encoding utf8

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "   Build completed successfully!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Release artifacts available in: $ReleaseDir`n"

Get-ChildItem -Path $ReleaseDir -File | Format-Table Name, @{Label="Size (MB)"; Expression={"{0:N2}" -f ($_.Length / 1MB)}}, LastWriteTime
