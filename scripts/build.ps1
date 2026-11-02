param([string[]] $Tasks = @('assembleDebug', 'testDebugUnitTest'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$localJdk = Get-ChildItem (Join-Path $projectRoot '.tools\jdk17') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
if (-not $env:JAVA_HOME) { throw 'JDK 17 is required. Run scripts\setup.ps1 or set JAVA_HOME.' }
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.cache\gradle-home'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.cache\android'
$sdkPath = Join-Path $projectRoot '.tools\android-sdk'
if (Test-Path -LiteralPath $sdkPath) { $env:ANDROID_HOME = $sdkPath }
if (-not $env:ANDROID_HOME -and -not (Test-Path 'local.properties')) { throw 'Android SDK 35 is required. Run scripts\setup.ps1.' }
$localGradle = Join-Path $projectRoot '.tools\gradle-8.11.1\bin\gradle.bat'
if (Test-Path -LiteralPath $localGradle) {
    & $localGradle @Tasks --console=plain
} else {
    & (Join-Path $projectRoot 'gradlew.bat') @Tasks --console=plain
}
exit $LASTEXITCODE
