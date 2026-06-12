# Membuat ikon launcher HermesPet dari sprite slime pixel-art.
# Jalankan dari root proyek:  pwsh scripts/make_icon.ps1
Add-Type -AssemblyName System.Drawing

# sprite ikon 16x16: slime + mata/kilau/senyum/pipi sudah "dibakar"
$map = @(
    ".......OO.......",
    "......OBBO......",
    ".......OO.......",
    "....OOOOOOOO....",
    "..OOBLLBBBBBOO..",
    ".OBLLBBBBBBBBBO.",
    ".OBLWEBBBBWEBBO.",
    "OBBBEEBBBBEEBBBO",
    "OBBBEEBBBBEEBBBO",
    "OBBBEEBBBBEEBBBO",
    "OBPPBBEBBEBBPPBO",
    "OBBBBBBEEBBSSSBO",
    ".OSBBBBBBSSSSSO.",
    ".OSSSSSSSSSSSSO.",
    "..OOOOOOOOOOOO..",
    "................"
)

$palette = @{
    'O' = '#2B4A23'; 'B' = '#7EDB63'; 'L' = '#B6F09C'; 'S' = '#4FA63E'
    'E' = '#2A2A35'; 'W' = '#FFFFFF'; 'P' = '#FF9EB5'
}
$bg = '#BFE8F7'  # langit pastel, full-bleed supaya aman dari mask launcher

$sizes = [ordered]@{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }

function Hex($h) {
    [System.Drawing.ColorTranslator]::FromHtml($h)
}

foreach ($dpi in $sizes.Keys) {
    $px = $sizes[$dpi]
    $bmp = New-Object System.Drawing.Bitmap($px, $px)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear((Hex $bg))

    # sprite menempati 80% tengah kanvas
    $inner = [math]::Floor($px * 0.8)
    $off = [math]::Floor(($px - $inner) / 2)

    for ($r = 0; $r -lt 16; $r++) {
        for ($c = 0; $c -lt 16; $c++) {
            $ch = $map[$r][$c]
            if ($palette.ContainsKey([string]$ch)) {
                $x0 = $off + [math]::Floor($c * $inner / 16)
                $y0 = $off + [math]::Floor($r * $inner / 16)
                $x1 = $off + [math]::Floor(($c + 1) * $inner / 16)
                $y1 = $off + [math]::Floor(($r + 1) * $inner / 16)
                $brush = New-Object System.Drawing.SolidBrush((Hex $palette[[string]$ch]))
                $g.FillRectangle($brush, $x0, $y0, ($x1 - $x0), ($y1 - $y0))
                $brush.Dispose()
            }
        }
    }
    $g.Dispose()

    $dir = "app/src/main/res/mipmap-$dpi"
    New-Item -ItemType Directory -Force $dir | Out-Null
    $out = Join-Path (Resolve-Path $dir) "ic_launcher.png"
    $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "OK $dir/ic_launcher.png (${px}px)"
}
