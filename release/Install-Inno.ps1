[CmdletBinding()]
param([Parameter(Mandatory)][string]$Destination)

$ErrorActionPreference = 'Stop'
$Destination = [IO.Path]::GetFullPath($Destination)
$installer = Join-Path ([IO.Path]::GetTempPath()) "input-control-inno-$([guid]::NewGuid()).exe"
try {
    Invoke-WebRequest 'https://github.com/jrsoftware/issrc/releases/download/is-6_7_4/innosetup-6.7.4.exe' -OutFile $installer
    if ((Get-FileHash -LiteralPath $installer).Hash -ne 'a549929319f9120716677c685535ca4671e4b937b5fdc87e2585f32d8eb30e8c') {
        throw 'Inno Setup download failed its pinned SHA-256 check.'
    }
    $process = Start-Process -FilePath $installer -ArgumentList '/CURRENTUSER','/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',('/DIR="' + $Destination + '"') -Wait -PassThru -WindowStyle Hidden
    if ($process.ExitCode -ne 0) { throw "Inno Setup installation failed ($($process.ExitCode))." }
} finally {
    Remove-Item -LiteralPath $installer -Force -ErrorAction SilentlyContinue
}
