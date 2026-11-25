param(
    [string]$Channel = "$PSScriptRoot/resources/gunnertimes-channel.json",
    [string]$Libraries = "$env:APPDATA/.minecraft/libraries"
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$javaHomeLine = java -XshowSettings:properties -version 2>&1 | Select-String '^\s*java.home = (.+)$' | Select-Object -First 1
$jdk = $javaHomeLine.Matches[0].Groups[1].Value
$gson = Join-Path $Libraries 'com/google/code/gson/gson/2.14.0/gson-2.14.0.jar'
$work = New-Item -ItemType Directory -Force (Join-Path $PSScriptRoot 'build')
$stage = New-Item -ItemType Directory (Join-Path $work ([Guid]::NewGuid().ToString('N')))
$classes = New-Item -ItemType Directory (Join-Path $stage 'classes')
$inputFiles = New-Item -ItemType Directory (Join-Path $stage 'input')
$sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'src') -Filter '*.java' -File | ForEach-Object FullName)
& "$jdk/bin/javac.exe" --release 25 -encoding UTF-8 -cp $gson -d $classes.FullName `
    "$PSScriptRoot/../updater/src/Updater.java" @sources
Get-ChildItem (Join-Path $PSScriptRoot 'resources') -File | Copy-Item -Destination $classes.FullName
Copy-Item -LiteralPath $Channel -Destination (Join-Path $classes 'gunnertimes-channel.json')
Push-Location $classes
try {
    & "$jdk/bin/jar.exe" xf $gson com/google/gson META-INF/LICENSE META-INF/NOTICE
} finally {
    Pop-Location
}
& "$jdk/bin/jar.exe" --create --file "$inputFiles/GunnertimesLauncher.jar" --main-class GunnertimesLauncher -C $classes.FullName .
& "$jdk/bin/jpackage.exe" --type app-image --name 'Gunnertimes' --app-version '1.2.1' `
    --input $inputFiles.FullName --main-jar GunnertimesLauncher.jar --dest $stage.FullName `
    --add-modules ALL-MODULE-PATH --jlink-options '--strip-debug --no-man-pages --no-header-files'
$archive = Join-Path $stage 'Gunnertimes-Windows-x64.zip'
[System.IO.Compression.ZipFile]::CreateFromDirectory((Join-Path $stage 'Gunnertimes'), $archive,
    [System.IO.Compression.CompressionLevel]::Optimal, $true)
Write-Output (Join-Path $stage 'Gunnertimes/Gunnertimes.exe')
Write-Output $archive
