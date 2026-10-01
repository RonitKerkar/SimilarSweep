$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$version = '8.11.1'
$cacheDir = Join-Path $env:LOCALAPPDATA 'SimilarSweepBuild'
New-Item -ItemType Directory -Force $cacheDir | Out-Null
$gradleExe = Join-Path $cacheDir "gradle-$version\bin\gradle.bat"
if (!(Test-Path $gradleExe)) {
    $zipPath = Join-Path $cacheDir 'gradle.zip'
    Invoke-WebRequest "https://services.gradle.org/distributions/gradle-$version-bin.zip" -OutFile $zipPath
    $expected = (Invoke-RestMethod "https://services.gradle.org/distributions/gradle-$version-bin.zip.sha256").Trim()
    if ((Get-FileHash $zipPath -Algorithm SHA256).Hash.ToLower() -ne $expected.ToLower()) { throw 'Gradle checksum mismatch' }
    Expand-Archive $zipPath -DestinationPath $cacheDir -Force
}
& $gradleExe --no-daemon assembleDebug lintDebug
if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
