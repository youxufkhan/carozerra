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

  Exits non-zero on the first failure. Artifacts land beside -OutDir.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Exe,
  [string]$OutDir = "smoke",
  [int]$WaitSeconds = 12
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if (-not (Test-Path $Exe)) { throw "no such exe: $Exe" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$Exe = (Resolve-Path $Exe).Path
$OutDir = (Resolve-Path $OutDir).Path

Write-Host "== pass 1: --selftest =="
$selftestPng = Join-Path $OutDir "selftest.png"
# The exe is built windowed (console=False), so `& $Exe` returns immediately
# and never sets $LASTEXITCODE -- PowerShell only waits on console
# subsystem programs. Start-Process + WaitForExit is the one that actually
# blocks, and the timeout means a hung render can't wedge the job.
$st = Start-Process -FilePath $Exe -ArgumentList @("--selftest", $selftestPng) `
                    -PassThru -NoNewWindow
if (-not $st.WaitForExit(60000)) {
  Stop-Process -Id $st.Id -Force
  throw "--selftest did not exit within 60s"
}
$st.Refresh()
if ($st.ExitCode -ne 0) { throw "--selftest exited $($st.ExitCode)" }
if (-not (Test-Path $selftestPng)) { throw "--selftest wrote no image" }
$size = (Get-Item $selftestPng).Length
Write-Host "selftest.png: $size bytes"
# a PNG of a rendered faceplate is tens of KB; a few hundred bytes means a
# flat fill that the colour check somehow passed
if ($size -lt 8000) { throw "selftest.png is suspiciously small ($size bytes)" }

Write-Host "== pass 2: interactive launch =="
$launched = Start-Process -FilePath $Exe -PassThru
Start-Sleep -Seconds $WaitSeconds

# Screenshot before asserting anything: on a red run this is the artifact that
# explains why, so it must exist even if the checks below throw.
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
  # a capture failure is a CI-environment problem, not an app problem — say so
  # and keep going rather than failing the build on it
  Write-Warning "screen capture failed: $($_.Exception.Message)"
}

# A onefile PyInstaller exe forks: Start-Process hands back the bootloader,
# and the window belongs to its child. So the launched PID's MainWindowHandle
# is routinely 0 (and it can even report HasExited) while the app is up and
# fine. Assert against the process tree by name instead.
$procs = @(Get-Process -Name ([System.IO.Path]::GetFileNameWithoutExtension($Exe)) -ErrorAction SilentlyContinue)
if ($procs.Count -eq 0) {
  throw "no carozerra process alive after $WaitSeconds s (launched pid $($launched.Id) exited with $($launched.ExitCode))"
}
Write-Host "$($procs.Count) process(es): $($procs.Id -join ', ')"

$win = $procs | Where-Object { $_.MainWindowHandle -ne [IntPtr]::Zero } | Select-Object -First 1
if (-not $win) {
  $procs | Stop-Process -Force
  throw "process is alive but no process in the tree created a top-level window"
}
Write-Host "window handle: $($win.MainWindowHandle), title: '$($win.MainWindowTitle)'"

# WM_CLOSE on the window owner; fall back to killing the tree so a hung exe
# can't wedge the job
if (-not $win.CloseMainWindow()) { Write-Host "CloseMainWindow() refused; killing" }
if (-not $win.WaitForExit(10000)) {
  Write-Host "still running after 10s; killing"
}
Get-Process -Name ([System.IO.Path]::GetFileNameWithoutExtension($Exe)) -ErrorAction SilentlyContinue |
  Stop-Process -Force -ErrorAction SilentlyContinue
Write-Host "== smoke passed =="
