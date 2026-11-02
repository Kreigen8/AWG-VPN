param([switch] $AcceptSdkLicenses)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
New-Item -ItemType Directory -Force -Path '.cache', '.tools' | Out-Null

function Get-VerifiedArchive([string] $Url, [string] $Destination, [string] $Sha256) {
    if (-not (Test-Path -LiteralPath $Destination)) {
        Invoke-WebRequest -Uri $Url -OutFile $Destination
    }
    $actual = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash
    if ($actual -ne $Sha256) { throw "Checksum mismatch: $Destination" }
}

if (-not (Test-Path '.tools\jdk17\jdk-17.0.20.1+1\bin\java.exe')) {
    Get-VerifiedArchive 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip' '.cache\jdk17.zip' 'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0'
    Expand-Archive -LiteralPath '.cache\jdk17.zip' -DestinationPath '.tools\jdk17' -Force
}
if (-not (Test-Path '.tools\gradle-8.11.1\bin\gradle.bat')) {
    Get-VerifiedArchive 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip' '.cache\gradle-8.11.1-bin.zip' 'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6'
    Expand-Archive -LiteralPath '.cache\gradle-8.11.1-bin.zip' -DestinationPath '.tools' -Force
}
if (-not (Test-Path '.tools\android-cli\cmdline-tools\bin\sdkmanager.bat')) {
    Get-VerifiedArchive 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' '.cache\android-commandlinetools.zip' '98b565cb657b012dae6794cefc0f66ae1efb4690c699b78a614b4a6a3505b003'
    Expand-Archive -LiteralPath '.cache\android-commandlinetools.zip' -DestinationPath '.tools\android-cli' -Force
}
$env:JAVA_HOME = Join-Path $projectRoot '.tools\jdk17\jdk-17.0.20.1+1'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.cache\android'
$sdkRoot = Join-Path $projectRoot '.tools\android-sdk'
New-Item -ItemType Directory -Force -Path $sdkRoot, $env:ANDROID_USER_HOME | Out-Null
$sdkManager = Join-Path $projectRoot '.tools\android-cli\cmdline-tools\bin\sdkmanager.bat'
if ($AcceptSdkLicenses) {
    1..50 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdkRoot" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
} else {
    & $sdkManager "--sdk_root=$sdkRoot" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
}
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Output 'Local Android toolchain ready. Run .\scripts\build.ps1.'
