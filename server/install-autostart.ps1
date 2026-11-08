$ErrorActionPreference = 'Stop'

# Startup shortcut for the DSK Remote server - no window of any kind.
#
#   target: wscript.exe  "start-hidden.vbs"
#
# wscript.exe is a GUI-subsystem program: it never allocates a console.
# Batch files (cmd.exe) always do, which is why the old shortcut produced a
# black window even with -WindowStyle Hidden. start-hidden.vbs then launches
# goose.exe via WshShell.Run(..., 0, False), i.e. hidden window, no wait.

# The folder this script lives in - so it works wherever you unpacked it.
$dir = $PSScriptRoot
$vbs = Join-Path $dir 'start-hidden.vbs'

if (-not (Test-Path $vbs)) { throw "missing $vbs" }

$startup = [Environment]::GetFolderPath('Startup')
$lnk     = Join-Path $startup 'DSK Remote server.lnk'

$w = New-Object -ComObject WScript.Shell
$s = $w.CreateShortcut($lnk)
$s.TargetPath       = "$env:SystemRoot\System32\wscript.exe"
$s.Arguments        = '"' + $vbs + '"'
$s.WorkingDirectory = $dir
$s.WindowStyle      = 7
$s.Description      = 'DSK Remote - goose server without a window'
$s.Save()

Write-Output ('shortcut : ' + $lnk)
Write-Output ('target   : ' + $s.TargetPath)
Write-Output ('args     : ' + $s.Arguments)
Write-Output ('exists   : ' + (Test-Path $lnk))
