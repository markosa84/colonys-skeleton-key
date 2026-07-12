<#
.SYNOPSIS
    Blacks out everything the reader never looks at, in every labelled frame.

.DESCRIPTION
    The fixtures are full-screen game screenshots: at 4K one costs ~12 MB, and the corpus once came
    to ~1.1 GB in the working tree and as much again in .git. Almost all of that is the game world
    around the lock - pixels LockReader never samples (CaptureBoxTest proves it never reads outside
    GameScreen's lock box) and pixels that compress badly, being detailed scenery.

    So each frame keeps its ORIGINAL DIMENSIONS - every absolute screen coordinate in the reader
    still lands on the right pixel, and TestFrames needs no special loader - but everything outside
    the kept regions is painted black, which PNG stores for almost nothing. Frames shrink ~5x.
    Pixels inside the kept regions are copied verbatim: the fixtures remain the exact calibration
    ground truth they were, and a shrunken frame is simply what GameScreen.captureLock() already
    hands the reader live (a lock box composited into an otherwise blank canvas).

    Kept, in 4K reference coordinates, mapped onto each frame's own viewport with the aspect-fit of
    vision/Viewport (a frame's pixel size IS its viewport, so nothing has to be told to the script):

      - the lock box      (GameScreen.LOCK_X0/Y0/W/H)   + BELT
      - the lockpick-counter box (GameScreen.PICKS_*)   + BELT

    The belt is slack for a future reader that samples a little wider. It is not slack for a
    reader that samples somewhere ELSE: run this on a COPY, keep the full-size originals archived
    off-repo, and re-run the suite before you throw anything away.

    KEEP THE CONSTANTS BELOW IN STEP WITH GameScreen. They are duplicated here on purpose - this
    script must run without building the project - but they are the same numbers.

.EXAMPLE
    .\scripts\shrink-frames.ps1 -Source C:\dev\frames-archive -Destination .\src\test\data\frames
    .\scripts\shrink-frames.ps1 -Source .\src\test\data\frames -WhatIf
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [string] $Source = (Join-Path (Split-Path $PSScriptRoot -Parent) 'src\test\data\frames'),
    [string] $Destination
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

# GameScreen's reference boxes, and the slack around them.
$LOCK = @{ X = 2450; Y = 300; W = 1300; H = 1120 }
$PICKS = @{ X = 3104; Y = 1616; W = 72; H = 56 }
$BELT = 150
# Viewport.REFERENCE
$REF_W = 3840.0
$REF_H = 2160.0

$src = [System.IO.Path]::GetFullPath($Source)
$dst = if ($Destination) { [System.IO.Path]::GetFullPath($Destination) } else { $src }
New-Item -ItemType Directory -Force $dst | Out-Null

# Viewport's aspect-fit: the 16:9 reference view is fitted inside the screen, anchored to its
# centre. A reference box maps with origin floored and extent ceiled, exactly like GameScreen.box.
function Get-MappedBox($box, $belt, $w, $h) {
    $scale = [Math]::Min($w / $REF_W, $h / $REF_H)
    $x0 = $box.X - $belt
    $y0 = $box.Y - $belt
    $x1 = $box.X + $box.W + $belt
    $y1 = $box.Y + $box.H + $belt
    $mx0 = [Math]::Floor($w / 2.0 + ($x0 - $REF_W / 2.0) * $scale)
    $my0 = [Math]::Floor($h / 2.0 + ($y0 - $REF_H / 2.0) * $scale)
    $mx1 = [Math]::Ceiling($w / 2.0 + ($x1 - $REF_W / 2.0) * $scale)
    $my1 = [Math]::Ceiling($h / 2.0 + ($y1 - $REF_H / 2.0) * $scale)
    # Clamp into the frame; a belt can hang off the edge at small viewports.
    $mx0 = [Math]::Max(0, $mx0); $my0 = [Math]::Max(0, $my0)
    $mx1 = [Math]::Min($w, $mx1); $my1 = [Math]::Min($h, $my1)
    if ($mx1 -le $mx0 -or $my1 -le $my0) { return $null }
    return New-Object System.Drawing.Rectangle([int]$mx0, [int]$my0, [int]($mx1 - $mx0), [int]($my1 - $my0))
}

$frames = Get-ChildItem $src -Recurse -File -Filter *.png | Sort-Object FullName
if (-not $frames) { throw "No frames under $src" }

# Anything that is not a frame (labels.txt) is not an image and travels verbatim.
if ($dst -ne $src) {
    foreach ($other in Get-ChildItem $src -Recurse -File -Exclude *.png) {
        $relative = $other.FullName.Substring($src.Length).TrimStart('\')
        $target = Join-Path $dst $relative
        if ($PSCmdlet.ShouldProcess($relative, 'copy')) {
            New-Item -ItemType Directory -Force (Split-Path $target -Parent) | Out-Null
            Copy-Item -Force $other.FullName $target
        }
    }
}

$before = 0L
$after = 0L
foreach ($frame in $frames) {
    $relative = $frame.FullName.Substring($src.Length).TrimStart('\')
    $target = Join-Path $dst $relative
    $before += $frame.Length

    if (-not $PSCmdlet.ShouldProcess($relative, 'shrink')) { continue }

    $image = [System.Drawing.Image]::FromFile($frame.FullName)
    try {
        $w = $image.Width
        $h = $image.Height
        $canvas = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
        try {
            $g = [System.Drawing.Graphics]::FromImage($canvas)
            try {
                $g.Clear([System.Drawing.Color]::Black)
                # PixelOffsetMode Half + NearestNeighbor: copy pixels 1:1, never resample.
                $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
                $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
                foreach ($box in @($LOCK, $PICKS)) {
                    $rect = Get-MappedBox $box $BELT $w $h
                    if ($rect) { $g.DrawImage($image, $rect, $rect, [System.Drawing.GraphicsUnit]::Pixel) }
                }
            } finally { $g.Dispose() }

            New-Item -ItemType Directory -Force (Split-Path $target -Parent) | Out-Null
            $temp = "$target.tmp"
            $canvas.Save($temp, [System.Drawing.Imaging.ImageFormat]::Png)
        } finally { $canvas.Dispose() }
    } finally { $image.Dispose() }

    Move-Item -Force $temp $target
    $after += (Get-Item $target).Length
}

if ($before -gt 0 -and $after -gt 0) {
    Write-Host ("{0} frames: {1:N0} MB -> {2:N0} MB ({3:N1}x smaller)" -f `
        $frames.Count, ($before / 1MB), ($after / 1MB), ($before / [double]$after)) -ForegroundColor Green
}
