param(
    [Parameter(Mandatory = $true)]
    [string]$AndroidApkPath,
    [string]$IosIpaPath,
    [string]$HarmonyHapPath,
    [string]$HarmonyAppProfilePath,
    [string]$AndroidVariant = 'debug',
    [string]$Target = ''
)

$ErrorActionPreference = 'Stop'

$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$androidConfigPath = Join-Path $workspace 'apps\mobile\android\app\build.gradle.kts'
$iosConfigPath = Join-Path $workspace 'apps\mobile\ios\project.yml'
$releaseRoot = Join-Path $workspace 'artifacts\releases'

$androidConfig = Get-Content -LiteralPath $androidConfigPath -Raw
$iosConfig = Get-Content -LiteralPath $iosConfigPath -Raw
$androidVersion = [regex]::Match($androidConfig, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$androidBuild = [regex]::Match($androidConfig, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$iosVersion = [regex]::Match($iosConfig, 'MARKETING_VERSION:\s*"([^"]+)"').Groups[1].Value
$iosBuild = [regex]::Match($iosConfig, 'CURRENT_PROJECT_VERSION:\s*"(\d+)"').Groups[1].Value

if (!$androidVersion -or !$androidBuild -or !$iosVersion -or !$iosBuild) {
    throw 'Unable to read both mobile version definitions.'
}
if ($androidVersion -ne $iosVersion -or $androidBuild -ne $iosBuild) {
    throw "Mobile versions do not match: Android $androidVersion ($androidBuild), iOS $iosVersion ($iosBuild)."
}

$apk = (Resolve-Path -LiteralPath $AndroidApkPath).Path
if ([IO.Path]::GetExtension($apk) -ne '.apk') {
    throw 'Android artifact must be an APK file.'
}

$safeVariant = ($AndroidVariant -replace '[^A-Za-z0-9._-]', '-').Trim('-')
$safeTarget = ($Target -replace '[^A-Za-z0-9._-]', '-').Trim('-')
$androidDirectory = Join-Path $releaseRoot "android\$androidVersion"
$iosDirectory = Join-Path $releaseRoot "ios\$iosVersion"
$harmonyDirectory = Join-Path $releaseRoot "harmonyos\$androidVersion"
New-Item -ItemType Directory -Force -Path $androidDirectory, $iosDirectory, $harmonyDirectory | Out-Null

$androidSuffix = if ($safeTarget) { "$safeVariant-$safeTarget" } else { $safeVariant }
$androidName = "shtong-android-$androidVersion-build$androidBuild-$androidSuffix.apk"
$androidDestination = Join-Path $androidDirectory $androidName
if ([IO.Path]::GetFullPath($apk) -ne [IO.Path]::GetFullPath($androidDestination)) {
    Copy-Item -LiteralPath $apk -Destination $androidDestination -Force
}
$androidHash = (Get-FileHash -LiteralPath $androidDestination -Algorithm SHA256).Hash
$androidInfo = Get-Item -LiteralPath $androidDestination

$androidManifest = [ordered]@{
    schemaVersion = 1
    platform = 'android'
    versionName = $androidVersion
    buildNumber = [int]$androidBuild
    status = 'ready'
    packageId = 'com.shtong.app'
    variant = $AndroidVariant
    target = $Target
    artifact = $androidName
    bytes = $androidInfo.Length
    sha256 = $androidHash
    publishedAt = (Get-Date).ToString('o')
}
$androidManifest | ConvertTo-Json -Depth 4 |
    Set-Content -LiteralPath (Join-Path $androidDirectory 'release.json') -Encoding utf8

$iosArtifact = $null
$iosStatus = 'pending_macos_signed_build'
if (![string]::IsNullOrWhiteSpace($IosIpaPath)) {
    $ipa = (Resolve-Path -LiteralPath $IosIpaPath).Path
    if ([IO.Path]::GetExtension($ipa) -ne '.ipa') {
        throw 'iOS artifact must be an IPA file.'
    }
    $iosName = "shtong-ios-$iosVersion-build$iosBuild.ipa"
    $iosDestination = Join-Path $iosDirectory $iosName
    if ([IO.Path]::GetFullPath($ipa) -ne [IO.Path]::GetFullPath($iosDestination)) {
        Copy-Item -LiteralPath $ipa -Destination $iosDestination -Force
    }
    $iosInfo = Get-Item -LiteralPath $iosDestination
    $iosArtifact = [ordered]@{
        name = $iosName
        bytes = $iosInfo.Length
        sha256 = (Get-FileHash -LiteralPath $iosDestination -Algorithm SHA256).Hash
    }
    $iosStatus = 'ready'
}

$iosManifest = [ordered]@{
    schemaVersion = 1
    platform = 'ios'
    versionName = $iosVersion
    buildNumber = [int]$iosBuild
    status = $iosStatus
    bundleId = 'com.shtong.app'
    artifact = $iosArtifact
    publishedAt = (Get-Date).ToString('o')
}
$iosManifest | ConvertTo-Json -Depth 4 |
    Set-Content -LiteralPath (Join-Path $iosDirectory 'release.json') -Encoding utf8

$harmonyArtifact = $null
$harmonyStatus = 'pending_harmony_project_build'
$harmonyBundle = $null
if (![string]::IsNullOrWhiteSpace($HarmonyHapPath)) {
    if ([string]::IsNullOrWhiteSpace($HarmonyAppProfilePath)) {
        throw 'HarmonyAppProfilePath is required when publishing a HAP.'
    }
    $hap = (Resolve-Path -LiteralPath $HarmonyHapPath).Path
    $harmonyProfile = (Resolve-Path -LiteralPath $HarmonyAppProfilePath).Path
    if ([IO.Path]::GetExtension($hap) -ne '.hap') {
        throw 'HarmonyOS artifact must be a HAP file.'
    }
    $harmonyConfig = Get-Content -LiteralPath $harmonyProfile -Raw
    $harmonyVersion = [regex]::Match($harmonyConfig, '["'']versionName["'']\s*:\s*["'']([^"'']+)["'']').Groups[1].Value
    $harmonyBuild = [regex]::Match($harmonyConfig, '["'']versionCode["'']\s*:\s*(\d+)').Groups[1].Value
    $harmonyBundle = [regex]::Match($harmonyConfig, '["'']bundleName["'']\s*:\s*["'']([^"'']+)["'']').Groups[1].Value
    if (!$harmonyVersion -or !$harmonyBuild) {
        throw 'Unable to read HarmonyOS versionName and versionCode from the app profile.'
    }
    if ($harmonyVersion -ne $androidVersion -or $harmonyBuild -ne $androidBuild) {
        throw "Mobile versions do not match: Android/iOS $androidVersion ($androidBuild), HarmonyOS $harmonyVersion ($harmonyBuild)."
    }
    $harmonyName = "shtong-harmonyos-$harmonyVersion-build$harmonyBuild.hap"
    $harmonyDestination = Join-Path $harmonyDirectory $harmonyName
    if ([IO.Path]::GetFullPath($hap) -ne [IO.Path]::GetFullPath($harmonyDestination)) {
        Copy-Item -LiteralPath $hap -Destination $harmonyDestination -Force
    }
    $harmonyInfo = Get-Item -LiteralPath $harmonyDestination
    $harmonyArtifact = [ordered]@{
        name = $harmonyName
        bytes = $harmonyInfo.Length
        sha256 = (Get-FileHash -LiteralPath $harmonyDestination -Algorithm SHA256).Hash
    }
    $harmonyStatus = 'ready'
}

$harmonyManifest = [ordered]@{
    schemaVersion = 1
    platform = 'harmonyos'
    versionName = $androidVersion
    buildNumber = [int]$androidBuild
    status = $harmonyStatus
    bundleName = if ($harmonyBundle) { $harmonyBundle } else { $null }
    artifact = $harmonyArtifact
    publishedAt = (Get-Date).ToString('o')
}
$harmonyManifest | ConvertTo-Json -Depth 4 |
    Set-Content -LiteralPath (Join-Path $harmonyDirectory 'release.json') -Encoding utf8

$index = [ordered]@{
    schemaVersion = 1
    versionName = $androidVersion
    buildNumber = [int]$androidBuild
    android = [ordered]@{
        status = 'ready'
        directory = "android/$androidVersion"
        artifact = $androidName
    }
    ios = [ordered]@{
        status = $iosStatus
        directory = "ios/$iosVersion"
        artifact = if ($iosArtifact) { $iosArtifact.name } else { $null }
    }
    harmonyos = [ordered]@{
        status = $harmonyStatus
        directory = "harmonyos/$androidVersion"
        artifact = if ($harmonyArtifact) { $harmonyArtifact.name } else { $null }
    }
    updatedAt = (Get-Date).ToString('o')
}
$index | ConvertTo-Json -Depth 4 |
    Set-Content -LiteralPath (Join-Path $releaseRoot 'current.json') -Encoding utf8

$index | ConvertTo-Json -Depth 4
