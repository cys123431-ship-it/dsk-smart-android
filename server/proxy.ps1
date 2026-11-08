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
# Ports
#   goose           127.0.0.1:<Port+1>  (3285) - loopback only, unreachable from outside
#   this proxy      0.0.0.0:<Port>      (3284) - this is the address the phone uses
#
# Started by start-hidden.vbs, stopped by Stop-DSK-Remote.bat.

param(
    [int]$Port = 3284,
    [string]$LogPath = ''
)

$ErrorActionPreference = 'Stop'

if (-not ('DskTcpProxy' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Net;
using System.Net.Sockets;
using System.Threading;

public static class DskTcpProxy
{
    public static string LogPath = "";
    public static object LogLock = new object();

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

    public static void Serve(int port, string targetHost, int targetPort)
    {
        var listener = new TcpListener(IPAddress.Any, port);
        listener.Start();
        Log("front door on 0.0.0.0:" + port + " -> " + targetHost + ":" + targetPort);

        while (true)
        {
            TcpClient client;
            try { client = listener.AcceptTcpClient(); }
            catch (Exception e) { Log("accept failed: " + e.Message); continue; }

            string remote = "-";
            try
            {
                remote = client.Client.RemoteEndPoint.ToString();
                client.NoDelay = true;

                var upstream = new TcpClient();
                upstream.NoDelay = true;
                upstream.Connect(targetHost, targetPort);

                var clientStream = client.GetStream();
                var upstreamStream = upstream.GetStream();

                var toUp = new Thread(() => Pump(clientStream, upstreamStream));
                var toDown = new Thread(() => Pump(upstreamStream, clientStream));
                toUp.IsBackground = true;
                toDown.IsBackground = true;

                var watch = new Thread(() =>
                {
                    toUp.Join();
                    try { client.Close(); } catch { }
                    try { upstream.Close(); } catch { }
                    toDown.Join();
                });
                watch.IsBackground = true;

                toUp.Start();
                toDown.Start();
                watch.Start();
            }
            catch (Exception e)
            {
                Log("[" + remote + "] " + e.Message);
                try { client.Close(); } catch { }
            }
        }
    }
}
'@
}

[DskTcpProxy]::LogPath = $LogPath
try {
    [DskTcpProxy]::Serve($Port, '127.0.0.1', $Port + 1)
} finally {
    [DskTcpProxy]::Log('front door stopped')
}
