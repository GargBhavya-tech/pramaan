$ErrorActionPreference = 'Stop'

$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
if (-not (Test-Path $edge)) {
    $edge = "C:\Program Files\Microsoft\Edge\Application\msedge.exe"
}

$pitchHtml = "file:///C:/Master_Brain/Projects/Pramaan/docs/submission/pitch-7slides.html"
$pitchPdf = "C:\Master_Brain\Projects\Pramaan\docs\submission\PRAMAAN-pitch-7slides.pdf"

$solutionHtml = "file:///C:/Master_Brain/Projects/Pramaan/docs/submission/solution-document.html"
$solutionPdf = "C:\Master_Brain\Projects\Pramaan\docs\submission\PRAMAAN-solution-2page.pdf"

Write-Host "Rendering Pitch PDF..."
Start-Process -FilePath $edge -ArgumentList @("--headless=new", "--disable-gpu", "--no-pdf-header-footer", "--print-to-pdf=$pitchPdf", $pitchHtml) -Wait

Write-Host "Rendering Solution PDF..."
Start-Process -FilePath $edge -ArgumentList @("--headless=new", "--disable-gpu", "--no-pdf-header-footer", "--print-to-pdf=$solutionPdf", $solutionHtml) -Wait

Write-Host "Copying to submission-package..."
Copy-Item "C:\Master_Brain\Projects\Pramaan\docs\submission\PRAMAAN-pitch-7slides.pptx" "C:\Master_Brain\Projects\Pramaan\submission-package\3-pitch-deck\" -Force
Copy-Item "C:\Master_Brain\Projects\Pramaan\docs\submission\PRAMAAN-pitch-full-10slides.pptx" "C:\Master_Brain\Projects\Pramaan\submission-package\3-pitch-deck\" -Force
Copy-Item $pitchPdf "C:\Master_Brain\Projects\Pramaan\submission-package\3-pitch-deck\" -Force
Copy-Item $solutionPdf "C:\Master_Brain\Projects\Pramaan\submission-package\4-solution-document\" -Force

Write-Host "Done!"
