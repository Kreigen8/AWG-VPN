$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$localJdk = Get-ChildItem '.tools\jdk17' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
$buildTools = Join-Path $projectRoot '.tools\android-sdk\build-tools\35.0.0'
$releaseApk = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path -LiteralPath $releaseApk)) { throw 'Signed release APK missing. Build assembleRelease first.' }
& (Join-Path $PSScriptRoot 'verify-apk-runtime.ps1') -ApkPath $releaseApk
$signature = & (Join-Path $buildTools 'apksigner.bat') verify --verbose --print-certs $releaseApk 2>&1
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
& (Join-Path $buildTools 'zipalign.exe') -c -P 16 4 $releaseApk
if ($LASTEXITCODE -ne 0) { throw 'APK zip alignment verification failed.' }
New-Item -ItemType Directory -Force -Path 'dist' | Out-Null
$metadata = Get-Content (Join-Path $projectRoot 'app\build\outputs\apk\release\output-metadata.json') -Raw | ConvertFrom-Json
$versionName = $metadata.elements[0].versionName
if ($versionName -notmatch '^[0-9A-Za-z._-]+$') { throw 'Invalid APK version metadata.' }
$sourceVersion = $versionName -replace '-preview$', ''
$apkName = "AWG-VPN-$versionName.apk"
$zipName = "AWG-VPN-$sourceVersion-source.zip"
Copy-Item -LiteralPath $releaseApk -Destination (Join-Path 'dist' $apkName) -Force
$signerLine = $signature | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest: ' } | Select-Object -First 1
if (-not $signerLine) { throw 'APK signing certificate digest missing.' }
$signerDigest = ($signerLine -replace '^Signer #1 certificate SHA-256 digest: ', '').Trim().ToLowerInvariant()
if ($signerDigest -notmatch '^[a-f0-9]{64}$') { throw 'Invalid signing certificate digest.' }
$updateManifest = [ordered]@{
    schemaVersion = 1
    packageName = $metadata.applicationId
    versionName = $versionName
    versionCode = $metadata.elements[0].versionCode
    minSdk = $metadata.minSdkVersionForDexing
    apkName = $apkName
    apkSize = (Get-Item -LiteralPath $releaseApk).Length
    sha256 = (Get-FileHash -LiteralPath $releaseApk -Algorithm SHA256).Hash.ToLowerInvariant()
    signerSha256 = $signerDigest
}
[IO.File]::WriteAllText((Join-Path $projectRoot 'dist\update.json'), ($updateManifest | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))

# Archive an explicit source allowlist. Local signing keys, credentials, build
# outputs, downloaded SDK/JDK files and caches are intentionally never traversed.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$sourceFiles = New-Object 'System.Collections.Generic.List[string]'
$rootNames = @('.gitignore', 'README.md', 'LICENSE', 'build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat')
foreach ($name in $rootNames) {
    $candidate = Join-Path $projectRoot $name
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { $sourceFiles.Add($candidate) }
}
foreach ($dir in @('app\src', 'vendor', 'scripts', 'gradle', 'docs', 'examples', '.github')) {
    $candidate = Join-Path $projectRoot $dir
    if (Test-Path -LiteralPath $candidate -PathType Container) {
        Get-ChildItem -LiteralPath $candidate -Recurse -File | Where-Object {
            $_.FullName -notmatch '[\\/]build[\\/]' -and $_.FullName -notmatch '[\\/]\.gradle[\\/]'
        } | ForEach-Object { $sourceFiles.Add($_.FullName) }
    }
}
foreach ($name in @('app\build.gradle', 'app\proguard-rules.pro')) {
    $sourceFiles.Add((Join-Path $projectRoot $name))
}
$archivePath = Join-Path $projectRoot (Join-Path 'dist' $zipName)
$stream = [IO.File]::Open($archivePath, [IO.FileMode]::Create)
$archive = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in ($sourceFiles | Sort-Object -Unique)) {
        $relative = $file.Substring($projectRoot.Length + 1).Replace('\', '/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file, ('AWG-VPN/' + $relative), [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose(); $stream.Dispose() }

$checksumLines = foreach ($name in @($apkName, $zipName, 'update.json')) {
    $hash = (Get-FileHash -LiteralPath (Join-Path 'dist' $name) -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $name"
}
[IO.File]::WriteAllLines((Join-Path $projectRoot 'dist\SHA256SUMS.txt'), $checksumLines)
[IO.File]::WriteAllLines((Join-Path $projectRoot 'dist\APK-SIGNATURE.txt'), [string[]] $signature)
Write-Output ($checksumLines -join "`n")
Write-Output 'Signature and 16 KiB zip alignment verified. Private signing material is excluded from the source archive.'
