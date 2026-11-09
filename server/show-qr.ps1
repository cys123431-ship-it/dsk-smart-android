<#
  show-qr.ps1 - draws the DSK pairing QR for the phone.

  The phone reads one code and is done: no address to type, no key to copy, no
  guessing which Wi-Fi or Tailscale address the PC answers on.

  The code is  DSK1|<host>:<port>|<kind>|<pc name>|<secret>  and the addresses
  come from the machine itself, so a new IP never needs editing anything.

  If python is missing the window simply says so; the addresses are already
  printed in the console, so pairing still works by hand.

  The key arrives in the DSK_QR_SECRET environment variable (DSK-Remote.bat sets it), not on
  the command line, where any process listing would show it. -Secret still works for testing.
  The PNGs hold the key too: they are released and deleted when the window closes, and any
  left behind by an earlier run (older versions kept them locked) are removed at start.
#>
[CmdletBinding()]
param(
    [string]$Secret = '',
    [Parameter(Mandatory = $true)][int]$Port,
    [string[]]$Lan = @(),
    [string[]]$Ts = @()
)

$ErrorActionPreference = 'Stop'

if (-not $Secret) { $Secret = $env:DSK_QR_SECRET }
Remove-Item Env:\DSK_QR_SECRET -ErrorAction SilentlyContinue
if (-not $Secret) {
    Write-Output 'NO_SECRET'
    exit 1
}

# Pairing images from earlier runs: they contain the key, so they must not pile up in TEMP.
Get-ChildItem -Path $env:TEMP -Filter 'dsk_qr_*.png' -ErrorAction SilentlyContinue |
    Remove-Item -Force -ErrorAction SilentlyContinue

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$gen = Join-Path $here 'qrcode_gen\qr_png.py'
$pcName = [Environment]::MachineName

function Find-Python {
    foreach ($name in @('python.exe', 'py.exe', 'python3.exe')) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd) {
            # py.exe needs no arguments to run a script, same as python
            return $cmd.Source
        }
    }
    $py = Join-Path $env:LOCALAPPDATA 'Programs\Python'
    if (Test-Path $py) {
        $found = Get-ChildItem $py -Filter 'python.exe' -Recurse -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}

function New-QrPng([string]$python, [string]$text, [string]$out, [int]$target) {
    if (Test-Path $out) { Remove-Item $out -Force -ErrorAction SilentlyContinue }
    & $python $gen --text $text --out $out --target $target 2>&1 | Out-Null
    if (Test-Path $out) { return $out } else { return $null }
}

function Invoke-QrWindow {
    param($Codes)

    $images = @()
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing

    $font = New-Object System.Drawing.Font('Segoe UI', 11)
    $fontHead = New-Object System.Drawing.Font('Segoe UI', 16, [System.Drawing.FontStyle]::Bold)
    $fontSmall = New-Object System.Drawing.Font('Segoe UI', 9)

    $perRow = [Math]::Min(2, $Codes.Count)
    $rows = [int][Math]::Ceiling($Codes.Count / $perRow)
    $cellW = 480
    $cellH = 610

    $form = New-Object System.Windows.Forms.Form
    $form.Text = "DSK Remote - 휴대폰으로 QR 을 찍으세요 ($pcName)"
    $form.StartPosition = 'CenterScreen'
    $form.ClientSize = New-Object System.Drawing.Size(($cellW * $perRow + 20), ($cellH * $rows + 90))
    $form.BackColor = [System.Drawing.Color]::White
    $form.MaximizeBox = $false

    $title = New-Object System.Windows.Forms.Label
    $title.Text = '휴대폰 DSK 앱 -> 설정 -> "PC 화면 QR 찍기" -> 이 화면을 찍으세요'
    $title.Font = $fontHead
    $title.AutoSize = $false
    $title.TextAlign = 'MiddleCenter'
    $title.SetBounds(10, 10, $form.ClientSize.Width - 20, 40)
    $form.Controls.Add($title)

    $hint = New-Object System.Windows.Forms.Label
    $hint.Text = 'PC 와 휴대폰이 같은 Wi-Fi 이면 Wi-Fi QR 이 가장 빠릅니다. 밖에서 쓰려면 Tailscale QR 을 찍으세요.'
    $hint.Font = $fontSmall
    $hint.TextAlign = 'MiddleCenter'
    $hint.ForeColor = [System.Drawing.Color]::DimGray
    $hint.SetBounds(10, 50, $form.ClientSize.Width - 20, 24)
    $form.Controls.Add($hint)

    $index = 0
    foreach ($code in $Codes) {
        $col = $index % $perRow
        $row = [int][Math]::Floor($index / $perRow)
        $x = 10 + $col * $cellW
        $y = 84 + $row * $cellH

        $picture = New-Object System.Windows.Forms.PictureBox
        $picture.SizeMode = 'Zoom'
        $picture.BorderStyle = 'FixedSingle'
        $picture.SetBounds(($x + 30), $y, ($cellW - 60), ($cellW - 60))
        # Image.FromFile keeps the file locked until the image is disposed; without the
        # Dispose below the PNG (which contains the key) could never be deleted.
        $image = [System.Drawing.Image]::FromFile($code.Path)
        $images += $image
        $picture.Image = $image
        $form.Controls.Add($picture)

        $caption = New-Object System.Windows.Forms.Label
        $caption.Text = "{0}`r`n{1}" -f $code.Caption, $code.Address
        $caption.Font = $font
        $caption.TextAlign = 'MiddleCenter'
        $caption.SetBounds($x, ($y + $cellW - 20), $cellW, 60)
        $form.Controls.Add($caption)

        $index++
    }

    $form.Add_Shown({ $form.Activate() })
    try {
        [void]$form.ShowDialog()
    } finally {
        $form.Dispose()
        foreach ($img in $images) { $img.Dispose() }
    }
}

# ---- build the codes ----
$codes = @()

foreach ($ip in $Lan) {
    $codes += [pscustomobject]@{
        Kind     = 'lan'
        Address  = "${ip}:$Port"
        Caption  = '같은 Wi-Fi'
        Text     = "DSK1|${ip}:$Port|lan|$pcName|$Secret"
    }
}

foreach ($ip in $Ts) {
    $codes += [pscustomobject]@{
        Kind     = 'tailscale'
        Address  = "${ip}:$Port"
        Caption  = 'Tailscale (어디서나)'
        Text     = "DSK1|${ip}:$Port|tailscale|$pcName|$Secret"
    }
}

if ($codes.Count -eq 0) {
    Write-Output 'NO_ADDRESS'
    exit 1
}

$python = Find-Python
if (-not $python -and -not (Test-Path $gen)) {
    Write-Output "NO_PYTHON"
    exit 2
}

$stamp = [DateTime]::Now.ToString('yyyyMMddHHmmss')
$made = @()
$i = 0
foreach ($code in $codes) {
    $out = Join-Path $env:TEMP ("dsk_qr_{0}_{1}.png" -f $stamp, $i)
    $png = New-QrPng -python $python -text $code.Text -out $out -target 400
    if ($png) {
        $made += [pscustomobject]@{
            Path    = $png
            Caption = $code.Caption
            Address = $code.Address
        }
    }
    $i++
}

if ($made.Count -eq 0) {
    Write-Output 'NO_PNG'
    exit 2
}

try {
    Invoke-QrWindow -Codes $made
} finally {
    foreach ($m in $made) {
        Remove-Item $m.Path -Force -ErrorAction SilentlyContinue
    }
}

Write-Output ("OK codes={0}" -f $made.Count)
