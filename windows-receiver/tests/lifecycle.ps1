# Run on a disposable desktop session with no companion currently open.
param([Parameter(Mandatory)][ipaddress]$BindAddress,
      [string]$Receiver=(Join-Path $PSScriptRoot '..\target\release\input-control-receiver.exe'))
$ErrorActionPreference='Stop'
$source=(Get-Item -LiteralPath $Receiver).FullName
$dir=Join-Path ([IO.Path]::GetTempPath()) ('input-control-lifecycle-'+[guid]::NewGuid())
[void][IO.Directory]::CreateDirectory($dir)
$fixture=Join-Path $dir 'input-control-receiver.exe'
[IO.File]::Copy($source,$fixture)
$previous=$env:LOCALAPPDATA
$env:LOCALAPPDATA=$dir
function Launch { Start-Process -FilePath $fixture -ArgumentList '--bind',$BindAddress -PassThru }
function Wait-Window($p) {
    for ($i=0;$i -lt 100;$i++) {
        $p.Refresh()
        if ($p.HasExited) { throw "Fixture exited early ($($p.ExitCode)). Quit other companions before this test." }
        if ($p.MainWindowHandle -ne 0) { return }
        Start-Sleep -Milliseconds 100
    }
    throw 'Fixture window did not open.'
}
function Current-Fixture { Get-Process -Name input-control-receiver -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $fixture } }
try {
    $p=Launch
    Wait-Window $p
    $burst=1..8 | ForEach-Object { Launch }
    foreach ($other in $burst) {
        if (!$other.WaitForExit(8000) -or $other.ExitCode -ne 0) { throw 'Duplicate remained active.' }
        $other.Dispose()
    }
    if (@(Current-Fixture).Count -ne 1) { throw 'Expected one receiver.' }
    # Simulate a crash only in the child started from this unique temporary copy.
    $p.Kill()
    $p.WaitForExit()
    $p=Launch
    Wait-Window $p
    $helper=Join-Path $PSScriptRoot '..\Maintain-Receiver.ps1'
    $client=[Net.Sockets.TcpClient]::new()
    try {
        $client.Connect($BindAddress,47831)
        & $helper -InstalledExe $fixture -Restart -BindAddress $BindAddress
        if (!$p.HasExited) { throw 'Original receiver survived restart.' }
    } finally { $client.Dispose() }
    & $helper -InstalledExe $fixture -NewExe $source -BindAddress $BindAddress
    if (@(Current-Fixture).Count -ne 1) { throw 'Update left an unexpected process count.' }
    Write-Output 'Passed single-instance, crash recovery, connected restart and live update.'
} finally {
    # Quit only windows verified to belong to this test copy.
    $env:LOCALAPPDATA=$previous
    foreach ($child in Current-Fixture) {
        if ($child.MainWindowHandle -ne 0) {
            Add-Type -TypeDefinition 'using System;using System.Runtime.InteropServices;public static class InputControlFixture { [DllImport("user32.dll")]public static extern bool PostMessage(IntPtr w,uint m,IntPtr a,IntPtr b); }'
            [void][InputControlFixture]::PostMessage($child.MainWindowHandle,273,[IntPtr]113,[IntPtr]::Zero)
            if (!$child.WaitForExit(10000)) { throw "Fixture did not exit: $fixture" }
        }
    }
    Write-Output "Test diagnostics: $dir"
}
