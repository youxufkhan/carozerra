<#
.SYNOPSIS
  Smoke-test a built carozerra.exe on a Windows CI runner.

.DESCRIPTION
  Two passes, because they fail differently:

    1. --selftest      the exe renders one frame to a PNG and exits. Proves the
                       Qt platform plugin loaded, assets/ unpacked under
                       sys._MEIPASS, and the paint path drew something.
    2. interactive     launch it the way a user would, confirm it is still
                       alive after a few seconds and owns a real top-level
                       window, screenshot the desktop, then close it. Catches
                       what a one-shot render can't: crashing in the event
                       loop, or never mapping a window at all.

  Exits non-zero on the first failure. Artifacts land in -OutDir.

  Run under Windows PowerShell 5.1, not pwsh 7 -- the screen capture uses
  System.Windows.Forms / System.Drawing, .NET Framework assemblies that
  Add-Type can't reliably resolve on .NET Core.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Exe,
  [string]$OutDir = "smoke",
  [int]$WaitSeconds = 12
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Start-Owned {
  <#
    Launch $Path under a System.Diagnostics.Process this script owns.

    Two things rule out the obvious alternatives. `& $Exe` never blocks and
    never sets $LASTEXITCODE, because the exe is built windowed
    (console=False) and PowerShell only waits on console-subsystem programs.
    And `Start-Process -PassThru` hands back an object whose ExitCode is
    routinely empty once the child has gone. Owning the Process object makes
    HasExited/ExitCode dependable and gives a real timeout.
  #>
  param(
    [Parameter(Mandatory = $true)][string]$Path,
    [string[]]$Arguments = @(),
    [switch]$Capture
  )
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $Path
  # ProcessStartInfo.ArgumentList doesn't exist on .NET Framework, so quote by
  # hand; every argument here is a path we generated
  $psi.Arguments = (($Arguments | ForEach-Object { '"' + $_ + '"' }) -join ' ')
  $psi.UseShellExecute = $false
  if ($Capture) {
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
  }
  return [System.Diagnostics.Process]::Start($psi)
}

if (-not (Test-Path $Exe)) { throw "no such exe: $Exe" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$Exe = (Resolve-Path $Exe).Path
$OutDir = (Resolve-Path $OutDir).Path
$procName = [System.IO.Path]::GetFileNameWithoutExtension($Exe)

Write-Host "== pass 1: --selftest =="
$selftestPng = Join-Path $OutDir "selftest.png"
$p = Start-Owned -Path $Exe -Arguments @("--selftest", $selftestPng) -Capture
# read to EOF before waiting: the output is a couple of lines, so this can't
# fill a pipe buffer and deadlock, and it keeps the exe's own report in the log
$out = $p.StandardOutput.ReadToEnd()
$err = $p.StandardError.ReadToEnd()
if (-not $p.WaitForExit(60000)) {
  $p.Kill()
  throw "--selftest did not exit within 60s"
}
if ($out) { Write-Host $out.Trim() }
if ($err) { Write-Host "stderr: $($err.Trim())" }
if ($p.ExitCode -ne 0) { throw "--selftest exited $($p.ExitCode)" }
if (-not (Test-Path $selftestPng)) { throw "--selftest wrote no image" }
$size = (Get-Item $selftestPng).Length
Write-Host "selftest.png: $size bytes"
# a PNG of a rendered faceplate is tens of KB; a few hundred bytes would mean a
# flat fill that the exe's own colour check somehow passed
if ($size -lt 8000) { throw "selftest.png is suspiciously small ($size bytes)" }

Write-Host "== pass 2: interactive launch =="
$launched = Start-Owned -Path $Exe
Start-Sleep -Seconds $WaitSeconds

# Screenshot before asserting anything: on a red run this is the artifact that
# explains why, so it has to exist even if the checks below throw.
$desktopPng = Join-Path $OutDir "desktop.png"
try {
  Add-Type -AssemblyName System.Windows.Forms, System.Drawing
  $screen = [System.Windows.Forms.SystemInformation]::VirtualScreen
  $bmp = New-Object System.Drawing.Bitmap $screen.Width, $screen.Height
  $gfx = [System.Drawing.Graphics]::FromImage($bmp)
  $gfx.CopyFromScreen($screen.Location, [System.Drawing.Point]::Empty, $screen.Size)
  $bmp.Save($desktopPng, [System.Drawing.Imaging.ImageFormat]::Png)
  $gfx.Dispose(); $bmp.Dispose()
  Write-Host "desktop.png: $((Get-Item $desktopPng).Length) bytes ($($screen.Width)x$($screen.Height))"
} catch {
  # a capture failure is a CI-environment problem, not an app problem
  Write-Warning "screen capture failed: $($_.Exception.Message)"
}

# A onefile PyInstaller exe forks: the process we started is the bootloader and
# the window belongs to its child, so the launched PID's MainWindowHandle is
# routinely 0 while the app is up and fine. Assert against the tree by name.
$procs = @(Get-Process -Name $procName -ErrorAction SilentlyContinue)
if ($procs.Count -eq 0) {
  $launched.Refresh()
  throw "no $procName process alive after $WaitSeconds s (bootloader pid $($launched.Id) exit code $($launched.ExitCode))"
}
Write-Host "$($procs.Count) process(es): $($procs.Id -join ', ')"

$win = $procs | Where-Object { $_.MainWindowHandle -ne [IntPtr]::Zero } | Select-Object -First 1
if (-not $win) {
  $procs | Stop-Process -Force
  throw "process is alive but no process in the tree created a top-level window"
}
Write-Host "window handle: $($win.MainWindowHandle), title: '$($win.MainWindowTitle)'"

# WM_CLOSE on the window's owner, then kill whatever is left so a hung exe
# can't wedge the job
if (-not $win.CloseMainWindow()) { Write-Host "CloseMainWindow() refused; killing" }
if (-not $win.WaitForExit(10000)) { Write-Host "still running after 10s; killing" }
Get-Process -Name $procName -ErrorAction SilentlyContinue |
  Stop-Process -Force -ErrorAction SilentlyContinue
Write-Host "== smoke passed =="
