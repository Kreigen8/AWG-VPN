param([Parameter(Mandatory = $true)][string] $ApkPath)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
$archive = [IO.Compression.ZipFile]::OpenRead($resolvedApk)
$definitions = New-Object 'System.Collections.Generic.HashSet[string]'
try {
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName -notmatch '^classes(?:\d+)?\.dex$') { continue }
        $memory = New-Object IO.MemoryStream
        $inputStream = $entry.Open()
        try { $inputStream.CopyTo($memory); $data = $memory.ToArray() }
        finally { $inputStream.Dispose(); $memory.Dispose() }
        if ([Text.Encoding]::ASCII.GetString($data, 0, 4) -ne "dex`n") { throw "Invalid DEX: $($entry.FullName)" }
        $stringsOffset = [BitConverter]::ToUInt32($data, 60)
        $typesOffset = [BitConverter]::ToUInt32($data, 68)
        $classCount = [BitConverter]::ToUInt32($data, 96)
        $classesOffset = [BitConverter]::ToUInt32($data, 100)
        for ($i = 0; $i -lt $classCount; $i++) {
            $typeIndex = [BitConverter]::ToUInt32($data, $classesOffset + 32 * $i)
            $stringIndex = [BitConverter]::ToUInt32($data, $typesOffset + 4 * $typeIndex)
            $position = [BitConverter]::ToUInt32($data, $stringsOffset + 4 * $stringIndex)
            while (($data[$position] -band 128) -ne 0) { $position++ }
            $position++ # Skip the final ULEB128 UTF-16 length byte.
            $end = $position
            while ($data[$end] -ne 0) { $end++ }
            $descriptor = [Text.Encoding]::UTF8.GetString($data, $position, $end - $position)
            [void]$definitions.Add($descriptor)
        }
    }
} finally { $archive.Dispose() }
$required = @(
    'Landroidx/core/content/ContextCompat;',
    'Landroidx/core/app/ActivityCompat;',
    'Landroidx/activity/ComponentActivity;',
    'Landroidx/camera/lifecycle/ProcessCameraProvider;',
    'Landroidx/camera/view/PreviewView;',
    'Landroidx/camera/core/ImageAnalysis;',
    'Landroidx/camera/camera2/Camera2Config;',
    'Lcom/google/zxing/qrcode/QRCodeReader;',
    'Lru/routevpn/app/QrCaptureActivity;',
    'Lru/routevpn/app/QrImageDecoder;',
    'Lru/routevpn/app/CameraQrDecoder;',
    'Lru/routevpn/app/QrPhotoReader;',
    'Lru/routevpn/app/RouteVpnService;',
    'Lru/routevpn/app/VpnApplication;',
    'Lru/routevpn/app/ConfigEditorActivity;',
    'Lru/routevpn/app/VpnTileService;',
    'Lru/routevpn/app/RestoreReceiver;',
    'Lru/routevpn/app/UpdateActivity;',
    'Lru/routevpn/app/UpdateManager;',
    'Lru/routevpn/app/UpdateApkVerifier;',
    'Landroidx/core/content/FileProvider;'
)
$missing = @($required | Where-Object { -not $definitions.Contains($_) })
if ($missing.Count -gt 0) { throw ('APK is missing required camera runtime class definitions: ' + ($missing -join ', ')) }
Write-Output "APK camera runtime verified: all $($required.Count) required classes are defined in DEX."
