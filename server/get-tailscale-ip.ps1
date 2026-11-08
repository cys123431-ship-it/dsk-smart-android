# DSK Remote helper: write this PC's Tailscale IPv4 address to
# %TEMP%\dsk_ts.tmp (one line, nothing at all when Tailscale is not up).
# Using "tailscale ip -4" through a pipe instead of a ">" redirect, because the
# command prints nothing when it has no console of its own.
param([Parameter(Mandatory=$true)][string]$Tailscale)

$target = Join-Path $env:TEMP 'dsk_ts.tmp'
Remove-Item $target -Force -ErrorAction SilentlyContinue

$raw = & $Tailscale ip -4 2>$null
$ip = @($raw) |
    ForEach-Object { ([string]$_).Trim() } |
    Where-Object { $_ -match '^\d+\.\d+\.\d+\.\d+' } |
    ForEach-Object { ($_ -split '/')[0] } |
    Select-Object -First 1

if ($ip) {
    Set-Content -Path $target -Value $ip -Encoding ascii
}
