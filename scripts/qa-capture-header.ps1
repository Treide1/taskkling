# QA capture for t-tlk0: launch the UI against a workspace, wait for the export to
# land, capture the window (title bar included) and report the OS title text.
#
# ASCII-only on purpose: PS 5.1 reads UTF-8-no-BOM as ANSI, and a stray non-ASCII
# char mangles into a parse error. That is also why readiness is detected by title
# LENGTH rather than by looking for the middle-dot separator.
#
# The window is located by PID, never by title -- the title is the thing under test.
param(
  [Parameter(Mandatory=$true)][string]$Workspace,
  [Parameter(Mandatory=$true)][string]$Out,
  [Parameter(Mandatory=$true)][string]$Jar,
  [Parameter(Mandatory=$true)][string]$Cli,
  [Parameter(Mandatory=$true)][string]$Java,
  # Optional: resize the window before capturing, to exercise the HeaderLadder's
  # narrow stages. MoveWindow on a background window does not steal focus.
  [int]$Width = 0,
  [int]$Height = 0
)

Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class TkCap {
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint flags);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out TkRect r);
  [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr h, int x, int y, int w, int ht, bool repaint);
}
[StructLayout(LayoutKind.Sequential)]
public struct TkRect { public int Left, Top, Right, Bottom; }
"@

$env:TASKKLING_BINARY = $Cli
$proc = Start-Process -FilePath $Java -ArgumentList @("-jar", $Jar, $Workspace) -PassThru

try {
  # Wait for the window to exist AND for its title to grow past the bare wordmark.
  # That transition IS the export landing, so it doubles as the readiness signal.
  $deadline = (Get-Date).AddSeconds(90)
  $hwnd = [IntPtr]::Zero
  $title = ""
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 250
    if ($proc.HasExited) { throw "UI process exited early with code $($proc.ExitCode)" }
    $proc.Refresh()
    if ($proc.MainWindowHandle -ne [IntPtr]::Zero) {
      $hwnd = $proc.MainWindowHandle
      $title = $proc.MainWindowTitle
      if ($title.Length -gt "taskkling".Length) { break }
    }
  }
  if ($hwnd -eq [IntPtr]::Zero) { throw "no window appeared within the deadline" }

  if ($Width -gt 0 -and $Height -gt 0) {
    [void][TkCap]::MoveWindow($hwnd, 100, 100, $Width, $Height, $true)
    Start-Sleep -Milliseconds 800
  }

  # Let the first frame with the resolved name actually paint before grabbing pixels.
  Start-Sleep -Milliseconds 1200
  $proc.Refresh()
  $title = $proc.MainWindowTitle

  $r = New-Object TkRect
  [void][TkCap]::GetWindowRect($hwnd, [ref]$r)
  $w = $r.Right - $r.Left
  $h = $r.Bottom - $r.Top

  $bmp = New-Object System.Drawing.Bitmap($w, $h)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $hdc = $g.GetHdc()
  # flag 2 = PW_RENDERFULLCONTENT: required for Skia/Compose GPU surfaces, and
  # captures a BACKGROUND window without stealing focus or injecting input.
  $ok = [TkCap]::PrintWindow($hwnd, $hdc, 2)
  $g.ReleaseHdc($hdc)
  $g.Dispose()
  if (-not $ok) { throw "PrintWindow failed" }
  $bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()

  Write-Output "TITLE=[$title]"
  Write-Output "SIZE=${w}x${h}"
  Write-Output "SAVED=$Out"
}
finally {
  if (-not $proc.HasExited) { $proc.Kill(); $proc.WaitForExit(5000) }
}
