[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Compiler,
    [Parameter(Mandatory)][string]$Receiver,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [ValidateSet('Local','PrepareUninstaller','Package','Verify')][string]$Mode = 'Local'
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$Compiler = (Get-Item -LiteralPath $Compiler).FullName
$Receiver = (Get-Item -LiteralPath $Receiver).FullName
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$metadata = ConvertFrom-StringData (Get-Content "$PSScriptRoot/version.properties" -Raw)
$version = $metadata.versionName
$releaseName = "InputControl-v$version-windows-x64"
$setup = Join-Path $OutputDirectory "$releaseName-setup.exe"
$portable = Join-Path $OutputDirectory "$releaseName-portable.zip"
$uninstallerDir = "$OutputDirectory-uninstaller"
$uninstallerExe = Join-Path $uninstallerDir 'uninstaller-to-sign.exe'
$resource = [Diagnostics.FileVersionInfo]::GetVersionInfo($Receiver)
if ($resource.ProductName -cne 'Input_Control' -or $resource.CompanyName -cne 'M. Chung' -or
    "$($resource.ProductMajorPart).$($resource.ProductMinorPart).$($resource.ProductBuildPart)" -ne $version -or
    [IO.Path]::GetFileName($Receiver) -cne 'input-control-receiver.exe') {
    throw 'Receiver resource metadata does not match the release.'
}
if ($Mode -eq 'Local' -and (Test-Path $OutputDirectory)) {
    throw 'Use a new output directory for local packages.'
}

function Assert-TrustedSignature([string]$Path) {
    if (!$env:WINDOWS_SIGNER_SUBJECT) { throw 'Set WINDOWS_SIGNER_SUBJECT to the exact verified signer certificate subject.' }
    $signature = Get-AuthenticodeSignature -LiteralPath $Path
    if ($signature.Status -ne 'Valid' -or !$signature.TimeStamperCertificate -or
        $signature.SignerCertificate.Subject -cne $env:WINDOWS_SIGNER_SUBJECT) {
        throw "Expected a trusted, timestamped signature from the configured publisher on $Path (status $($signature.Status))."
    }
}

function Get-UninstallerTemplate {
    $files = @(Get-ChildItem -LiteralPath $uninstallerDir -File -Filter 'uninst-*.e32')
    if ($files.Count -ne 1) { throw 'Expected exactly one Inno Setup uninstaller candidate.' }
    $files[0].FullName
}

if ($Mode -ne 'Local') { Assert-TrustedSignature $Receiver }
if ($Mode -eq 'Verify') {
    Assert-TrustedSignature $uninstallerExe
    Assert-TrustedSignature $setup
} else {
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $arguments = @(
        "/DReleaseVersion=$version", "/DReceiverDir=$([IO.Path]::GetDirectoryName($Receiver))",
        "/DPackageDir=$OutputDirectory", "$PSScriptRoot/InputControl.iss"
    )
    if ($Mode -ne 'Local') {
        if ($Mode -eq 'PrepareUninstaller') {
            New-Item -ItemType Directory -Path $uninstallerDir | Out-Null
        } else {
            Assert-TrustedSignature $uninstallerExe
            Copy-Item -LiteralPath $uninstallerExe -Destination (Get-UninstallerTemplate) -Force
        }
        $arguments = @('/DProduction', "/DUninstallerDir=$uninstallerDir") + $arguments
    }
    # Inno's first production pass writes the unsigned uninstaller and exits with a compiler error.
    & $Compiler @arguments
    $compilerExit = $LASTEXITCODE
    if ($Mode -eq 'PrepareUninstaller') {
        Copy-Item -LiteralPath (Get-UninstallerTemplate) -Destination $uninstallerExe
        if ($compilerExit -ne 2 -or (Get-AuthenticodeSignature $uninstallerExe).Status -ne 'NotSigned' -or (Test-Path $setup)) {
            throw 'Inno Setup did not produce the expected uninstaller signing request.'
        }
        if ($env:GITHUB_OUTPUT) { "path=$uninstallerExe" >> $env:GITHUB_OUTPUT }
        return
    }
    if ($compilerExit -ne 0 -or !(Test-Path -LiteralPath $setup -PathType Leaf)) {
        throw "Inno Setup compilation failed ($compilerExit)."
    }
    Compress-Archive -LiteralPath $Receiver -DestinationPath $portable
}

$setupResource = [Diagnostics.FileVersionInfo]::GetVersionInfo($setup)
if ($setupResource.CompanyName.Trim() -cne 'M. Chung' -or $setupResource.ProductName.Trim() -cne 'Input_Control' -or
    "$($setupResource.FileMajorPart).$($setupResource.FileMinorPart).$($setupResource.FileBuildPart)" -ne $version) {
    throw 'Installer resource metadata does not match the release.'
}
$archive = [IO.Compression.ZipFile]::OpenRead($portable)
try {
    if ($archive.Entries.Count -ne 1 -or $archive.Entries[0].FullName -cne 'input-control-receiver.exe') {
        throw 'Unexpected portable archive contents.'
    }
    $stream = $archive.Entries[0].Open()
    try {
        if ((Get-FileHash -InputStream $stream).Hash -ne (Get-FileHash -LiteralPath $Receiver).Hash) {
            throw 'The portable executable differs from the packaged receiver.'
        }
    } finally { $stream.Dispose() }
} finally { $archive.Dispose() }
