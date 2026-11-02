$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$localJdk = Get-ChildItem '.tools\jdk17' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
if (-not $env:JAVA_HOME) { throw 'JDK 17 is required.' }
if (Test-Path '.signing\routevpn-release.jks') { throw 'Release signing key already exists; preserve it for app updates.' }
New-Item -ItemType Directory -Force -Path '.signing' | Out-Null
$random = New-Object byte[] 48
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($random)
$rng.Dispose()
$signingPassword = [Convert]::ToBase64String($random)
try {
    $env:ROUTEVPN_SIGNING_PASSWORD = $signingPassword
    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore '.signing\routevpn-release.jks' -storetype JKS -storepass:env ROUTEVPN_SIGNING_PASSWORD -keypass:env ROUTEVPN_SIGNING_PASSWORD -alias routevpn -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=RouteVPN Local Build, OU=Personal Android App, O=RouteVPN'
    if ($LASTEXITCODE -ne 0) { throw 'Signing key creation failed.' }
    [IO.File]::WriteAllText((Join-Path $projectRoot '.signing\signing.properties'), "keyAlias=routevpn`nstorePassword=$signingPassword`nkeyPassword=$signingPassword`n")
} finally {
    Remove-Item Env:ROUTEVPN_SIGNING_PASSWORD -ErrorAction SilentlyContinue
    $signingPassword = $null
}
Write-Output 'Signing key saved privately in .signing. Back up this folder securely for future updates.'
