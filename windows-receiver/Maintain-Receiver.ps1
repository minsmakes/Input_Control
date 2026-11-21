# Run as the same interactive user as Input Control. No elevation is needed.
[CmdletBinding(DefaultParameterSetName='Restart')]
param(
    [Parameter(Mandatory)][string]$InstalledExe,
    [Parameter(Mandatory,ParameterSetName='Update')][string]$NewExe,
    [Parameter(Mandatory,ParameterSetName='Restart')][switch]$Restart,
    [ipaddress]$BindAddress
)
$ErrorActionPreference='Stop'
$installed=(Get-Item -LiteralPath $InstalledExe).FullName
$logDir=Join-Path $env:LOCALAPPDATA 'Input Control Receiver'
[void][IO.Directory]::CreateDirectory($logDir)
function Write-Diagnostic([string]$message) {
    $line="$(Get-Date -Format o) maintenance pid=$PID $message"
    Write-Verbose $line
    try { [IO.File]::AppendAllText((Join-Path $logDir 'maintenance.log'),$line+[Environment]::NewLine) } catch { Write-Warning $_ }
}
# Enumerate only companion windows in this desktop. Verify the owning process
# path and retain its handle across shutdown so PID reuse cannot target a successor.
if (-not ('InputControlMaintenance' -as [type])) { Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class InputControlMaintenance {
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern IntPtr FindWindowEx(IntPtr parent,IntPtr after,string cls,IntPtr title);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr w,out uint pid);
 [DllImport("user32.dll",SetLastError=true)] public static extern bool PostMessage(IntPtr w,uint msg,IntPtr wp,IntPtr lp);
}
"@
}
function Get-ReceiverWindow {
    $h=[IntPtr]::Zero
    while ($true) {
        $h=[InputControlMaintenance]::FindWindowEx([IntPtr]::Zero,$h,'InputControlCompanion',[IntPtr]::Zero)
        if ($h -eq [IntPtr]::Zero) { return }
        [uint32]$owner=0
        [void][InputControlMaintenance]::GetWindowThreadProcessId($h,[ref]$owner)
        $proc=Get-Process -Id $owner -ErrorAction SilentlyContinue
        if ($proc -and $proc.Path -eq $installed) {
            $null=$proc.Handle
            return @{Window=$h;Process=$proc}
        }
    }
}
function Enter-Mutex($mutex,[int]$timeout) {
    try { return $mutex.WaitOne($timeout) }
    catch [Threading.AbandonedMutexException] { return $true }
}
function Start-Receiver {
    $opts=@{FilePath=$installed;PassThru=$true}
    if ($BindAddress) { $opts.ArgumentList=@('--bind',$BindAddress.ToString()) }
    $started=Start-Process @opts
    try {
        $deadline=[DateTime]::UtcNow.AddSeconds(12)
        do {
            $window=Get-ReceiverWindow
            if ($window) { $window.Process.Dispose();return }
            if ($started.HasExited) { throw "Receiver exited during startup (code $($started.ExitCode))." }
            Start-Sleep -Milliseconds 100
        } while ([DateTime]::UtcNow -lt $deadline)
        throw 'Timed out waiting for the companion window.'
    } finally { $started.Dispose() }
}
$maintenance=[Threading.Mutex]::new($false,'Local\InputControl.WindowsCompanion.Maintenance.v1')
$instance=[Threading.Mutex]::new($false,'Local\InputControl.WindowsCompanion.Instance.v1')
$gated=$false
$owned=$false
$stage=$null
$backup=$null
$replaced=$false
$stopped=$false
try {
    $owned=Enter-Mutex $maintenance 0
    if (!$owned) { throw 'Another receiver maintenance operation is active.' }
    Write-Diagnostic "Starting $($PSCmdlet.ParameterSetName) for '$installed'"
    if ([Diagnostics.FileVersionInfo]::GetVersionInfo($installed).ProductName -notin @('Input Control','Input_Control')) { throw "Unexpected installed executable '$installed'." }
    if ($NewExe) {
        $source=(Get-Item -LiteralPath $NewExe).FullName
        if ($source -eq $installed) { throw 'Build into a separate staging directory first.' }
        $stage="$installed.$([guid]::NewGuid()).stage"
        [IO.File]::Copy($source,$stage)
        if ([Diagnostics.FileVersionInfo]::GetVersionInfo($stage).ProductName -notin @('Input Control','Input_Control')) { throw "Invalid receiver build '$source'." }
    }
    $running=Get-ReceiverWindow
    if ($running) {
        try {
            Write-Diagnostic "Requesting Exit from '$installed' pid=$($running.Process.Id)"
            if (![InputControlMaintenance]::PostMessage($running.Window,273,[IntPtr]113,[IntPtr]::Zero)) { throw 'Windows rejected the Exit request.' }
            if (!$running.Process.WaitForExit(15000)) { throw 'Receiver is still closing. The executable was left in place.' }
            $stopped=$true
        } finally { $running.Process.Dispose() }
    }
    $gated=Enter-Mutex $instance 4000
    if (!$gated) { throw 'A receiver instance is active. Close it from its tray and retry.' }
    if ($stage) {
        Write-Diagnostic "Checking exclusive access and replacing '$installed' from '$source'"
        [IO.File]::Open($installed,'Open','ReadWrite','None').Dispose()
        $backup="$installed.$([guid]::NewGuid()).backup"
        [IO.File]::Replace($stage,$installed,$backup)
        $replaced=$true
    }
    $instance.ReleaseMutex();$gated=$false
    Start-Receiver
    Write-Diagnostic "Receiver window ready for '$installed'"
    if ($backup) {
        try { [IO.File]::Delete($backup) }
        catch { Write-Diagnostic "Updated successfully; backup cleanup failed for '$backup': $_" }
    }
} catch {
    $failure=$_
    Write-Diagnostic "Failed for '$installed': $($failure.Exception.GetBaseException().Message) HRESULT=$($failure.Exception.GetBaseException().HResult)"
    if ($replaced -and $backup -and [IO.File]::Exists($backup)) {
        if (!$gated) { $gated=Enter-Mutex $instance 0 }
        if ($gated) {
            try { [IO.File]::Replace($backup,$installed,"$backup.failed"); Write-Diagnostic "Restored original '$installed'" }
            catch { Write-Diagnostic "Rollback failed. Original backup '$backup': $_" }
        } else { Write-Diagnostic "Executable may be in use; retained original '$backup'" }
    }
    if ($gated) { $instance.ReleaseMutex();$gated=$false }
    if ($stopped -and (!$replaced -or ![IO.File]::Exists($backup))) {
        try { Start-Receiver } catch { Write-Diagnostic "Recovery launch failed: $_" }
    }
    throw $failure
} finally {
    if ($stage -and [IO.File]::Exists($stage)) {
        try { [IO.File]::Delete($stage) } catch { Write-Diagnostic "Cannot clean staging file '$stage': $_" }
    }
    if ($gated) { $instance.ReleaseMutex() }
    if ($owned) { $maintenance.ReleaseMutex() }
    $instance.Dispose();$maintenance.Dispose()
}
