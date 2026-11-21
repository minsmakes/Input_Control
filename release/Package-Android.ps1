[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Sdk,
    [Parameter(Mandatory)][string]$Bundletool,
    [Parameter(Mandatory)][string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$env:LC_ALL = 'C'
$metadata = ConvertFrom-StringData (Get-Content "$PSScriptRoot/version.properties" -Raw)
$signing = ConvertFrom-StringData (Get-Content "$PSScriptRoot/android-signing.properties" -Raw)
$expectedSigner = $signing.releaseCertificateSha256
$appId = 'io.inputcontrol'
$build = Join-Path $PSScriptRoot '../android-app/app/build/outputs'
$tools = Join-Path $Sdk 'build-tools/36.0.0'
$apkSigner = Join-Path $tools $(if ($IsWindows) { 'apksigner.bat' } else { 'apksigner' })
$aapt = Join-Path $tools $(if ($IsWindows) { 'aapt.exe' } else { 'aapt' })
$zipalign = Join-Path $tools $(if ($IsWindows) { 'zipalign.exe' } else { 'zipalign' })
$Bundletool = (Get-Item -LiteralPath $Bundletool).FullName
if ((Get-FileHash -LiteralPath $Bundletool).Hash -ne 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29') {
    throw 'Expected the pinned bundletool 1.18.3 JAR.'
}
foreach ($name in 'ANDROID_KEYSTORE_PATH','ANDROID_STORE_PASSWORD','ANDROID_KEY_ALIAS','ANDROID_KEY_PASSWORD') {
    if (![Environment]::GetEnvironmentVariable($name)) { throw "Missing $name. Release signing requires the existing key." }
}
$store = (Get-Item -LiteralPath $env:ANDROID_KEYSTORE_PATH).FullName

function Confirm-Certificate([string[]]$Report) {
    $fingerprints = @($Report | Select-String '^[ \t]*SHA256:\s*([A-Fa-f0-9:]+)\s*$' |
        ForEach-Object { $_.Matches[0].Groups[1].Value -replace ':','' })
    if ($fingerprints.Count -ne 1 -or $fingerprints[0] -ne $expectedSigner) {
        throw 'Signing certificate differs from the published Android release identity.'
    }
}

function Confirm-NativePayload([string]$Archive, [string]$Prefix) {
    $lib = if ($Prefix) { "$Prefix/lib" } else { 'lib' }
    $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        $native = @($zip.Entries.FullName | Where-Object { $_ -like "$lib/*.so" } | Sort-Object)
        $expected = @("$lib/arm64-v8a/libinput_control.so", "$lib/x86_64/libinput_control.so")
        if ($expected | Where-Object { $_ -cnotin $native }) { throw "Missing Input_Control libraries in $Archive." }
        foreach ($entry in $native) {
            if ($entry -cnotmatch '^(.*/)?lib/(arm64-v8a|x86_64)/[^/]+\.so$') {
                throw "Unsupported native library path: $entry."
            }
            $stream = $zip.GetEntry($entry).Open()
            try {
                $bytes = [IO.MemoryStream]::new()
                try {
                    $stream.CopyTo($bytes)
                    $elf = $bytes.ToArray()
                } finally { $bytes.Dispose() }
                if ($elf[0] -ne 127 -or [Text.Encoding]::ASCII.GetString($elf, 1, 3) -cne 'ELF' -or
                    $elf[4] -ne 2 -or $elf[5] -ne 1) { throw "Expected 64-bit ELF in $entry." }
                $machine = if ($entry -like '*/arm64-v8a/*') { 183 } else { 62 }
                if ([BitConverter]::ToUInt16($elf, 18) -ne $machine) { throw "Wrong CPU architecture in $entry." }
                $offset = [int][BitConverter]::ToUInt64($elf, 32)
                $entrySize = [BitConverter]::ToUInt16($elf, 54)
                $count = [BitConverter]::ToUInt16($elf, 56)
                $loads = 0
                for ($index = 0; $index -lt $count; $index++) {
                    $header = $offset + $index * $entrySize
                    if ([BitConverter]::ToUInt32($elf, $header) -eq 1) {
                        $loads++
                        if ([BitConverter]::ToUInt64($elf, $header + 48) -lt 16384) {
                            throw "Native library $entry has a load segment aligned below 16 KB."
                        }
                    }
                }
                if (!$loads) { throw "No load segments in $entry." }
            } finally { $stream.Dispose() }
        }
    } finally { $zip.Dispose() }
}

$certificate = & keytool '-J-Duser.language=en' -list -v -keystore $store -alias $env:ANDROID_KEY_ALIAS -storepass:env ANDROID_STORE_PASSWORD
if ($LASTEXITCODE) { throw 'Could not read the existing signing certificate.' }
Confirm-Certificate $certificate
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Use a new output directory for signed packages.' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$work = Join-Path ([IO.Path]::GetTempPath()) "input-control-android-$([guid]::NewGuid())"
New-Item -ItemType Directory -Path $work | Out-Null
try {
    $name = "InputControl-v$($metadata.versionName)-android"
    $apk = Join-Path $work "$name.apk"
    $bundle = Join-Path $work "$name.aab"
    $aligned = Join-Path $work 'aligned.apk'
    & $zipalign -P 16 -f 4 (Join-Path $build 'apk/release/app-release-unsigned.apk') $aligned
    if ($LASTEXITCODE) { throw 'APK alignment failed.' }
    & $apkSigner sign --ks $store --ks-key-alias $env:ANDROID_KEY_ALIAS --ks-pass env:ANDROID_STORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD --v4-signing-enabled false --out $apk $aligned
    if ($LASTEXITCODE) { throw 'APK signing failed.' }
    & jarsigner -keystore $store -storepass:env ANDROID_STORE_PASSWORD -keypass:env ANDROID_KEY_PASSWORD -signedjar $bundle (Join-Path $build 'bundle/release/app-release.aab') $env:ANDROID_KEY_ALIAS
    if ($LASTEXITCODE) { throw 'App Bundle signing failed.' }

    $signature = & $apkSigner verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0 -or
        @($signature | Select-String 'certificate SHA-256 digest:').Count -ne 1 -or
        !($signature -contains "Signer #1 certificate SHA-256 digest: $expectedSigner")) {
        throw 'APK verification failed or the signer differs from the published release.'
    }
    $signature | Write-Output
    & $zipalign -c -P 16 4 $apk
    if ($LASTEXITCODE) { throw 'Signed APK failed 16 KB alignment verification.' }
    Confirm-NativePayload $apk ''

    $badging = & $aapt dump badging $apk
    $package = "package: name='$appId' versionCode='$($metadata.versionCode)' versionName='$($metadata.versionName)'"
    if ($LASTEXITCODE -ne 0 -or !($badging | Where-Object { $_.StartsWith($package) }) -or
        $badging -notcontains "application-label:'Input_Control'" -or $badging -notcontains "sdkVersion:'28'" -or
        $badging -notcontains "targetSdkVersion:'36'" -or $badging -contains 'application-debuggable') {
        throw 'APK identity, SDK level, label, version, or release flag is incorrect.'
    }

    # Android app certificates are typically self-signed. Strict exit 4 is a trust-chain warning.
    # Strict mode also flags unsigned entries separately, which must fail verification.
    & jarsigner '-J-Duser.language=en' -verify -strict $bundle
    if ($LASTEXITCODE -notin @(0, 4)) { throw 'App Bundle signature verification failed.' }
    $certificate = & keytool '-J-Duser.language=en' -printcert -jarfile $bundle
    if ($LASTEXITCODE) { throw 'Could not read the App Bundle signing certificate.' }
    Confirm-Certificate $certificate
    & java -jar $Bundletool validate "--bundle=$bundle"
    if ($LASTEXITCODE) { throw 'App Bundle validation failed.' }
    Confirm-NativePayload $bundle 'base'
    $config = & java -jar $Bundletool dump config "--bundle=$bundle"
    if ($LASTEXITCODE -ne 0 -or ($config -join "`n") -notmatch 'PAGE_ALIGNMENT_16K') {
        throw 'App Bundle must request 16 KB page alignment.'
    }
    $manifest = & java -jar $Bundletool dump manifest "--bundle=$bundle" --module=base
    if ($LASTEXITCODE) { throw 'Could not inspect the App Bundle manifest.' }
    $node = ([xml]($manifest -join "`n")).DocumentElement
    $android = 'http://schemas.android.com/apk/res/android'
    if ($node.GetAttribute('package') -cne $appId -or
        $node.GetAttribute('versionCode', $android) -ne $metadata.versionCode -or
        $node.GetAttribute('versionName', $android) -cne $metadata.versionName -or
        $node.'uses-sdk'.GetAttribute('minSdkVersion', $android) -ne '28' -or
        $node.'uses-sdk'.GetAttribute('targetSdkVersion', $android) -ne '36' -or
        $node.application.GetAttribute('debuggable', $android) -eq 'true') {
        throw 'App Bundle identity, version, SDK level, or release flag is incorrect.'
    }
    New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
    Copy-Item -LiteralPath $apk,$bundle -Destination $OutputDirectory
} finally {
    foreach ($file in (Get-ChildItem -LiteralPath $work -File)) {
        Remove-Item -LiteralPath $file.FullName -Force
    }
    Remove-Item -LiteralPath $work
}
