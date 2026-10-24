# Builds the PRAMAAN demo video from things that exist: the pitch slides, the station screens rendered from the real UI
# code (./gradlew :desktop:showcaseRender), and the text of two real commands (tools/video/terminal-*.txt).
#
# The voice is Windows' own speech synthesizer, so it sounds synthetic; the video says on screen that the station screens
# are a SIMULATED session. Nothing here is a live camera, and nothing is a liveness result.
#
#   ./gradlew :desktop:showcaseRender
#   powershell -ExecutionPolicy Bypass -File tools/video/make_video.ps1
#
# Output: build/video/PRAMAAN-demo.mp4, PRAMAAN-demo.srt, and the per-scene images and audio.
# Needs: Windows PowerShell 5.1 (System.Speech), ffmpeg + ffprobe on the PATH, Microsoft Edge.

param(
    [string]$Out = "build/video",
    [double]$Tail = 0.7
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$outDir = Join-Path $repo $Out
$img = Join-Path $outDir "img"
$aud = Join-Path $outDir "audio"
$seg = Join-Path $outDir "seg"
foreach ($d in @($outDir, $img, $aud, $seg)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }

$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
if (-not (Test-Path $edge)) { $edge = "C:\Program Files\Microsoft\Edge\Application\msedge.exe" }
$pitch = Join-Path $repo "docs\submission\pitch-7slides.html"
$showcase = Join-Path $repo "build\showcase"
if (-not (Test-Path (Join-Path $showcase "beats.json"))) { throw "Run ./gradlew :desktop:showcaseRender first." }

function Shoot([string]$url, [string]$png, [int]$w = 1280, [int]$h = 720) {
    if (Test-Path $png) { Remove-Item $png }
    Start-Process -FilePath $edge -ArgumentList @("--headless=new", "--disable-gpu", "--hide-scrollbars",
        "--window-size=$w,$h", "--screenshot=`"$png`"", "`"$url`"") -Wait -WindowStyle Hidden
    if (-not (Test-Path $png)) { throw "screenshot failed: $url" }
}

function FileUrl([string]$p) { "file:///" + ($p -replace '\\', '/') }

# ---- 1. slides of the pitch at 1280x720, one at a time (the HTML holds all seven)
$src = Get-Content $pitch -Raw -Encoding UTF8
foreach ($n in 1, 2, 3, 6, 7) {
    $css = "<style>.slide{display:none !important} .slide:nth-of-type($n){display:block !important}</style></head>"
    $tmp = Join-Path (Split-Path $pitch) "_video$n.html"
    [IO.File]::WriteAllText($tmp, $src.Replace("</head>", $css), (New-Object Text.UTF8Encoding($false)))
    Shoot (FileUrl $tmp) (Join-Path $img "slide-$n.png")
    Remove-Item $tmp
}

# ---- 2. terminal scenes from the text of real commands
function Terminal([string]$txt, [string]$title, [string]$png) {
    $body = [Net.WebUtility]::HtmlEncode((Get-Content $txt -Raw -Encoding UTF8))
    $html = @"
<!doctype html><html><head><meta charset="utf-8"><style>
html,body{margin:0;background:#0b1220;color:#e6edf3;font-family:Consolas,'Cascadia Mono',monospace}
.bar{background:#1f2933;padding:12px 24px;font:600 20px 'Segoe UI',sans-serif;color:#cfe0f2}
pre{margin:0;padding:26px 34px;font-size:25px;line-height:1.5;white-space:pre-wrap}
</style></head><body><div class="bar">$title</div><pre>$body</pre></body></html>
"@
    $f = Join-Path $outDir "term.html"
    [IO.File]::WriteAllText($f, $html, (New-Object Text.UTF8Encoding($false)))
    Shoot (FileUrl $f) $png
    Remove-Item $f
}
Terminal (Join-Path $PSScriptRoot "terminal-binding.txt") "Capture binding: each attack is a test" (Join-Path $img "term-binding.png")
Terminal (Join-Path $PSScriptRoot "terminal-red-team.txt") "Our own red team (SYNTHETIC data)" (Join-Path $img "term-redteam.png")

# ---- 3. the notice card shown before the station screens
$card = @"
<!doctype html><html><head><meta charset="utf-8"><style>
html,body{margin:0;width:1280px;height:720px;background:#14202e;color:#fff;font-family:'Segoe UI',sans-serif}
.w{padding:150px 120px}.t{display:inline-block;background:#f5a623;color:#14202e;font-weight:700;font-size:28px;padding:8px 18px;border-radius:6px}
h1{font-size:54px;margin:34px 0 18px}p{font-size:30px;line-height:1.5;color:#cfe0f2;max-width:1000px}
</style></head><body><div class="w"><span class="t">SIMULATED SESSION</span>
<h1>The next screens are the real station UI</h1>
<p>Driven by simulated engine events. No camera or device is connected, and nothing here is a liveness result.</p></div></body></html>
"@
$cf = Join-Path $outDir "card.html"
[IO.File]::WriteAllText($cf, $card, (New-Object Text.UTF8Encoding($false)))
Shoot (FileUrl $cf) (Join-Path $img "card-simulated.png")
Remove-Item $cf

# ---- 4. the scenes, in order: image, narration
$beats = Get-Content (Join-Path $showcase "beats.json") -Raw -Encoding UTF8 | ConvertFrom-Json
function Beat([string]$id) {
    $b = $beats | Where-Object { $_.id -eq $id }
    if (-not $b) { throw "no showcase beat $id" }
    @{ image = (Join-Path $showcase $b.file); text = $b.narration }
}
$scenes = @(
    @{ image = (Join-Path $img "slide-1.png"); text = "PRAMAAN, which means proof. Face liveness and presentation attack detection for the MOSIP Registration Client, for MOSIP Decode 2026, problem statement four. One honest note first: this is a working engine that has not yet been measured on real attacks." },
    @{ image = (Join-Path $img "slide-2.png"); text = "The preview is unsigned. The capture that is stored is signed. Nothing links the two. So whoever controls the preview can pass liveness with one face while another face is enrolled. On Android, the client never even sees the preview." },
    @{ image = (Join-Path $img "slide-3.png"); text = "PRAMAAN defines the two interfaces the problem statement asks for: a frame source that returns frames and the signed capture from the same object, and a liveness engine that takes frames in and gives states, guidance and a decision out." },
    @{ image = (Join-Path $img "card-simulated.png"); text = "The next screens are the real station user interface, driven by a simulated session. No camera or device is connected." },
    (Beat "sign-in"),
    (Beat "resident"),
    (Beat "challenge"),
    (Beat "step-done"),
    (Beat "accepted"),
    (Beat "retry"),
    (Beat "exception"),
    (Beat "approval"),
    (Beat "hindi"),
    (Beat "fails-closed"),
    @{ image = (Join-Path $img "term-binding.png"); text = "Now the capture binding. Four locks run before any accept: the session nonce echoed inside the device signed capture, a hash chain over the frames, identity continuity, and time. Each attack is a test, and each is rejected. These locks work on unmodified S B I." },
    @{ image = (Join-Path $img "term-redteam.png"); text = "We also attacked ourselves. This is synthetic data. With the passive model assumed fooled and no flash light, the fusion accepted four of eighty five display pitches. With the flash cue, none. We logged it, proposed a fix, and did not turn it on." },
    @{ image = (Join-Path $img "slide-6.png"); text = "What we verify, and what we do not claim. Six hundred eighty six automated tests pass. We have not measured an error rate on real attacks, because no real corpus exists. It has not run on a real device or a phone, and no user has tried the screens yet." },
    @{ image = (Join-Path $img "slide-7.png"); text = "Next: one real experiment, a consented corpus, and a real device. We would like to ask MOSIP how an S B I extension should be proposed. The code is M P L two point zero, like MOSIP. Thank you." }
)

# ---- 5. narrate each scene, then encode it
Add-Type -AssemblyName System.Speech
$voice = New-Object System.Speech.Synthesis.SpeechSynthesizer
$voice.Rate = 0
"Voice: " + $voice.Voice.Name
$list = @()
$srt = New-Object Text.StringBuilder
$clock = 0.0
$i = 0
$cue = 0
foreach ($s in $scenes) {
    $i++
    $n = "{0:00}" -f $i
    $wav = Join-Path $aud "scene-$n.wav"
    $voice.SetOutputToWaveFile($wav)
    $voice.Speak(($s.text -replace 'PRAMAAN', 'Pramaan'))
    $voice.SetOutputToNull()
    $len = [double](& ffprobe -v error -show_entries format=duration -of csv=p=0 $wav)
    $dur = [Math]::Round($len + $Tail, 2)
    $mp4 = Join-Path $seg "scene-$n.mp4"
    & ffmpeg -y -v error -loop 1 -framerate 30 -i $s.image -i $wav -t $dur `
        -vf "scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=0x14202e,format=yuv420p" `
        -af apad -c:v libx264 -preset medium -crf 20 -r 30 -c:a aac -b:a 128k -ar 44100 -ac 1 $mp4
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed on scene $n" }
    $list += "file '" + ($mp4 -replace '\\', '/') + "'"

    # captions: the narration split into sentences, each shown for its share of the scene
    $sentences = @([regex]::Split($s.text, '(?<=[.?!])\s+') | Where-Object { $_.Trim().Length -gt 0 })
    $chars = ($sentences | ForEach-Object { $_.Length } | Measure-Object -Sum).Sum
    $t = $clock
    foreach ($sent in $sentences) {
        $cue++
        $share = $len * ($sent.Length / $chars)
        $a = [TimeSpan]::FromSeconds($t)
        $b = [TimeSpan]::FromSeconds($t + $share)
        [void]$srt.AppendLine("$cue")
        [void]$srt.AppendLine(("{0:hh\:mm\:ss}," -f $a) + ("{0:000}" -f $a.Milliseconds) + " --> " + ("{0:hh\:mm\:ss}," -f $b) + ("{0:000}" -f $b.Milliseconds))
        [void]$srt.AppendLine($sent.Trim())
        [void]$srt.AppendLine("")
        $t += $share
    }
    $clock += $dur
}
$voice.Dispose()

$listFile = Join-Path $outDir "concat.txt"
[IO.File]::WriteAllLines($listFile, $list)
$joined = Join-Path $outDir "joined.mp4"
& ffmpeg -y -v error -f concat -safe 0 -i $listFile -c copy $joined
if ($LASTEXITCODE -ne 0) { throw "concat failed" }

$srtFile = Join-Path $outDir "PRAMAAN-demo.srt"
[IO.File]::WriteAllText($srtFile, $srt.ToString(), (New-Object Text.UTF8Encoding($false)))
$final = Join-Path $outDir "PRAMAAN-demo.mp4"
& ffmpeg -y -v error -i $joined -i $srtFile -c copy -c:s mov_text -metadata:s:s:0 language=eng $final
if ($LASTEXITCODE -ne 0) { throw "subtitle mux failed" }

$total = [double](& ffprobe -v error -show_entries format=duration -of csv=p=0 $final)
"Done: $final  ($([Math]::Round($total,1)) s, $($scenes.Count) scenes)"
