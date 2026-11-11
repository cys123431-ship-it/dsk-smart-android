# DSK Remote - TCP proxy (front door)
#
# Why this exists
#   "goose serve" only accepts connections that arrive from 127.0.0.1. A phone on the
#   same Wi-Fi, or on Tailscale, arrives from another address, so goose answers
#   "403 Forbidden" and the app looks broken even though the PC is fine.
#
#   This proxy is the front door the phone talks to: it listens on 0.0.0.0:<Port>
#   and, for every connection, opens its own connection to 127.0.0.1:<Port+1> where
#   goose really runs. Bytes are copied both ways, so the WebSocket upgrade and every
#   frame after it pass through untouched.
#
#   The copying is written in C# and compiled in memory by Add-Type: PowerShell
#   script blocks run on foreign threads lose their variables, which silently killed
#   an earlier version of this file.
#
# When goose is not there
#   The two halves are separate processes, and more than once goose was gone while this
#   door kept listening: the phone then saw an open port that hung up on it, with no hint
#   why. Two things happen now when goose does not answer:
#     1. if -GooseExe was given, goose is started again (at most once every 30 seconds)
#        and the connection goes through as soon as it is up - the phone user may be
#        far from the PC and cannot restart anything;
#     2. if goose still is not there, the phone gets a real "HTTP 502" answer, which the
#        app shows as "PC reachable, goose down" instead of a bare connection error.
#   goose is only ever restarted when GOOSE_SERVER__SECRET_KEY is set in this process:
#   starting it without a key would open the PC to anyone who can reach this port.
#
# Ports
#   goose           127.0.0.1:<Port+1>  (3285) - loopback only, unreachable from outside
#   this proxy      0.0.0.0:<Port>      (3284) - this is the address the phone uses
#
# Started by start-hidden.vbs / DSK-Remote.bat, stopped by Stop-DSK-Remote.bat.

param(
    [int]$Port = 3284,
    [string]$LogPath = '',
    [string]$GooseExe = '',
    [string]$GooseArgs = ''
)

$ErrorActionPreference = 'Stop'

if (-not ('DskTcpProxy' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

public static class DskTcpProxy
{
    public static string LogPath = "";
    public static object LogLock = new object();

    // Empty GooseExe = never restart goose (see the header of proxy.ps1).
    public static string GooseExe = "";
    public static string GooseArgs = "";
    static object ReviveLock = new object();
    static DateTime LastRevive = DateTime.MinValue;

    public static void Log(string text)
    {
        if (string.IsNullOrEmpty(LogPath)) return;
        lock (LogLock)
        {
            try
            {
                System.IO.File.AppendAllText(
                    LogPath,
                    DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "  " + text + Environment.NewLine);
            }
            catch { }
        }
    }

    public static void Pump(NetworkStream from, NetworkStream to)
    {
        var buffer = new byte[16384];
        try
        {
            int read;
            while ((read = from.Read(buffer, 0, buffer.Length)) > 0)
            {
                to.Write(buffer, 0, read);
                to.Flush();
            }
        }
        catch { }
        try { to.Dispose(); } catch { }
    }

    static TcpClient Dial(string host, int port)
    {
        var upstream = new TcpClient();
        try
        {
            upstream.NoDelay = true;
            upstream.Connect(host, port);
            return upstream;
        }
        catch
        {
            try { upstream.Close(); } catch { }
            return null;
        }
    }

    // Starts goose again when it is gone. Returns true when it answers afterwards.
    static bool Revive(string host, int port)
    {
        lock (ReviveLock)
        {
            // Another connection may have brought it back while this one waited for the lock.
            var probe = Dial(host, port);
            if (probe != null) { try { probe.Close(); } catch { } return true; }

            if (string.IsNullOrEmpty(GooseExe)) return false;
            if ((DateTime.Now - LastRevive).TotalSeconds < 30) return false;
            LastRevive = DateTime.Now;

            Log("goose is not answering on " + host + ":" + port + " - starting it again");
            try
            {
                var info = new ProcessStartInfo(GooseExe, GooseArgs);
                info.UseShellExecute = false;
                info.CreateNoWindow = true;
                Process.Start(info);
            }
            catch (Exception e)
            {
                Log("could not start goose: " + e.Message);
                return false;
            }

            for (int i = 0; i < 60; i++)
            {
                Thread.Sleep(250);
                probe = Dial(host, port);
                if (probe != null)
                {
                    try { probe.Close(); } catch { }
                    Log("goose is back on " + host + ":" + port);
                    return true;
                }
            }
            Log("goose did not come back within 15 seconds");
            return false;
        }
    }

    // Tells the phone, in HTTP, that goose is not running. Reading the request first matters:
    // closing a socket with unread data resets it, and the phone would never see the answer.
    static void Refuse(TcpClient client)
    {
        try
        {
            var stream = client.GetStream();
            stream.ReadTimeout = 2000;
            var buffer = new byte[4096];
            var seen = new StringBuilder();
            try
            {
                while (seen.Length < 16384 && seen.ToString().IndexOf("\r\n\r\n", StringComparison.Ordinal) < 0)
                {
                    int read = stream.Read(buffer, 0, buffer.Length);
                    if (read <= 0) break;
                    seen.Append(Encoding.ASCII.GetString(buffer, 0, read));
                }
            }
            catch { }

            string body = "goose is not running behind DSK Remote. Start DSK-Remote.bat on the PC again.";
            string head =
                "HTTP/1.1 502 Bad Gateway\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: " + Encoding.UTF8.GetByteCount(body) + "\r\n" +
                "Connection: close\r\n\r\n";
            byte[] answer = Encoding.UTF8.GetBytes(head + body);
            stream.Write(answer, 0, answer.Length);
            stream.Flush();
            try { client.Client.Shutdown(SocketShutdown.Send); } catch { }
            Thread.Sleep(200);
        }
        catch { }
        try { client.Close(); } catch { }
    }

    static void Handle(TcpClient client, string targetHost, int targetPort)
    {
        string remote = "-";
        try
        {
            remote = client.Client.RemoteEndPoint.ToString();
            client.NoDelay = true;

            var upstream = Dial(targetHost, targetPort);
            if (upstream == null && Revive(targetHost, targetPort)) upstream = Dial(targetHost, targetPort);
            if (upstream == null)
            {
                Log("[" + remote + "] goose is not running on " + targetHost + ":" + targetPort + " - answered 502");
                Refuse(client);
                return;
            }

            var clientStream = client.GetStream();
            var upstreamStream = upstream.GetStream();

            var toUp = new Thread(() => Pump(clientStream, upstreamStream));
            var toDown = new Thread(() => Pump(upstreamStream, clientStream));
            toUp.IsBackground = true;
            toDown.IsBackground = true;
            toUp.Start();
            toDown.Start();

            toUp.Join();
            try { client.Close(); } catch { }
            try { upstream.Close(); } catch { }
            toDown.Join();
        }
        catch (Exception e)
        {
            Log("[" + remote + "] " + e.Message);
            try { client.Close(); } catch { }
        }
    }

    public static void Serve(int port, string targetHost, int targetPort)
    {
        var listener = new TcpListener(IPAddress.Any, port);
        listener.Start();
        Log("front door on 0.0.0.0:" + port + " -> " + targetHost + ":" + targetPort +
            (string.IsNullOrEmpty(GooseExe) ? "" : " (restarts goose when it is gone)"));

        while (true)
        {
            TcpClient client;
            try { client = listener.AcceptTcpClient(); }
            catch (Exception e) { Log("accept failed: " + e.Message); continue; }

            // One thread per connection: a slow goose restart must not stop others from
            // being accepted.
            var accepted = client;
            var worker = new Thread(() => Handle(accepted, targetHost, targetPort));
            worker.IsBackground = true;
            worker.Start();
        }
    }
}
'@
}

[DskTcpProxy]::LogPath = $LogPath

# goose is restarted only with its key in place; without the key it would serve anyone.
if ($GooseExe -and (Test-Path -LiteralPath $GooseExe) -and $env:GOOSE_SERVER__SECRET_KEY) {
    if (-not $GooseArgs) { $GooseArgs = "serve --host 127.0.0.1 --port $($Port + 1) --platform desktop" }
    [DskTcpProxy]::GooseExe = $GooseExe
    [DskTcpProxy]::GooseArgs = $GooseArgs
} elseif ($GooseExe) {
    [DskTcpProxy]::Log('goose restart is off: goose.exe not found or GOOSE_SERVER__SECRET_KEY is not set')
}

try {
    [DskTcpProxy]::Serve($Port, '127.0.0.1', $Port + 1)
} finally {
    [DskTcpProxy]::Log('front door stopped')
}
