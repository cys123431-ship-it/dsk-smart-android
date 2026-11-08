' DSK Remote - starts both halves with NO window at all.
'
' Why a .vbs file:
'   A batch file always gets a console window, even when started by
'   "powershell -WindowStyle Hidden" - cmd.exe still allocates one. That console
'   is the black box that kept popping up. wscript.exe has no console, and
'   ".Run(..., 0, False)" starts a program with a hidden window.
'
' Two processes, because goose only trusts loopback:
'   goose  -> 127.0.0.1:<PORT+1>   (3285)  never reachable from the phone
'   proxy  -> 0.0.0.0:<PORT>       (3284)  the address the phone dials
'   goose answers "403 Forbidden" to any client that is not 127.0.0.1, which is
'   exactly why the phone could never connect when goose was exposed directly.
'
' Start by double-clicking, or let the startup shortcut run it.
' Already running? It does nothing.

Option Explicit

Dim fso, shell, here, settingsPath, logPath
Dim secret, port, workDir, gooseExe, goosePort
Dim ts, line, key, val

Set fso   = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

here = fso.GetParentFolderName(WScript.ScriptFullName)
settingsPath = fso.BuildPath(here, "settings.txt")
logPath      = fso.BuildPath(here, "server.log")

If Not fso.FileExists(settingsPath) Then
    MsgBox "settings.txt not found:" & vbCrLf & settingsPath, 16, "DSK Remote"
    WScript.Quit 1
End If

' ---- read settings -------------------------------------------------
secret   = ""
port     = "3284"
workDir  = fso.BuildPath(here, "workspace")
gooseExe = ""

Set ts = fso.OpenTextFile(settingsPath, 1, False)
Do Until ts.AtEndOfStream
    line = Trim(ts.ReadLine)
    If Len(line) > 0 And Left(line, 1) <> "#" And InStr(line, "=") > 0 Then
        key = Trim(Left(line, InStr(line, "=") - 1))
        val = Trim(Mid(line, InStr(line, "=") + 1))
        Select Case UCase(key)
            Case "SECRET"  : secret  = val
            Case "PORT"    : port    = val
            Case "WORKDIR" : If Len(val) > 0 Then workDir = val
            Case "GOOSE"   : gooseExe = val
        End Select
    End If
Loop
ts.Close

If Len(secret) = 0 Then
    MsgBox "SECRET is empty in settings.txt.", 16, "DSK Remote"
    WScript.Quit 1
End If
If Len(gooseExe) = 0 Or Not fso.FileExists(gooseExe) Then
    MsgBox "goose.exe not found (check GOOSE in settings.txt):" & vbCrLf & gooseExe, 16, "DSK Remote"
    WScript.Quit 1
End If

goosePort = CStr(CLng(port) + 1)

' ---- already running? ----------------------------------------------
If PortBusy(port) Then
    ' The front door is already up; a second copy would only fail to bind.
    WScript.Quit 0
End If

' ---- start ---------------------------------------------------------
shell.Environment("Process")("GOOSE_SERVER__SECRET_KEY") = secret
shell.Environment("Process")("GOOSE_WORKING_DIR")        = workDir

' The file is recreated on each start so it only describes the current run.
On Error Resume Next
If fso.FileExists(logPath) Then fso.DeleteFile logPath, True
On Error Goto 0

Dim logFile
Set logFile = fso.OpenTextFile(logPath, 8, True)
logFile.WriteLine Now & "  DSK Remote starting"

' goose: loopback only, never 0.0.0.0 - that is the whole reason for the proxy.
Dim gooseCmd
gooseCmd = """" & gooseExe & """ serve --host 127.0.0.1 --port " & goosePort & " --platform desktop"
shell.CurrentDirectory = workDir
shell.Run gooseCmd, 0, False
logFile.WriteLine Now & "  goose on 127.0.0.1:" & goosePort

' Give goose a moment to bind before the proxy opens its front door.
Dim waited, up
up = False
For waited = 1 To 40
    WScript.Sleep 250
    If PortBusy(goosePort) Then
        up = True
        Exit For
    End If
Next
If Not up Then logFile.WriteLine Now & "  WARNING goose did not open 127.0.0.1:" & goosePort

Dim proxyCmd, ps
ps = shell.ExpandEnvironmentStrings("%SystemRoot%") & "\System32\WindowsPowerShell\v1.0\powershell.exe"
proxyCmd = """" & ps & """ -NoProfile -ExecutionPolicy Bypass -File """ & _
           fso.BuildPath(here, "proxy.ps1") & """ -Port " & port & " -LogPath """ & logPath & """"
shell.Run proxyCmd, 0, False
logFile.WriteLine Now & "  proxy on 0.0.0.0:" & port
logFile.Close

WScript.Quit 0

' True when something is LISTENING on the given TCP port.
Function PortBusy(p)
    Dim exec, out
    PortBusy = False
    On Error Resume Next
    Set exec = shell.Exec("cmd /c netstat -ano -p TCP | findstr LISTENING | findstr :" & p)
    If Err.Number = 0 Then
        out = exec.StdOut.ReadAll
        If InStr(out, ":" & p) > 0 Then PortBusy = True
    End If
    Err.Clear
    On Error Goto 0
End Function
