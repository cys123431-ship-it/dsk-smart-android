# DSK Remote helper: write the usable IPv4 addresses, one per line.
# Called by DSK-Remote.bat. The output is a plain file so cmd never has to
# deal with PowerShell quoting.
$out = Get-NetIPAddress -AddressFamily IPv4 -AddressState Preferred -ErrorAction SilentlyContinue |
    ForEach-Object { [string]$_.IPAddress } |
    Where-Object { $_ -notlike '127.*' -and $_ -notlike '169.254.*' -and $_ -notlike '100.*' }
$target = Join-Path $env:TEMP 'dsk_ipaddrs.tmp'
if ($out) {
    Set-Content -Path $target -Value $out -Encoding ascii
} elseif (Test-Path $target) {
    Remove-Item $target -Force -ErrorAction SilentlyContinue
}
