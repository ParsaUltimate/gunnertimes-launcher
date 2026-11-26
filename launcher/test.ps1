param([Parameter(Mandatory)][string]$ApplicationImage, [switch]$Ui)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$image = (Resolve-Path -LiteralPath $ApplicationImage).Path
$classes = New-Item -ItemType Directory -Path (Join-Path $env:TEMP ("gunnertimes-test-" + [Guid]::NewGuid()))
$jar = Join-Path $image 'app/GunnertimesLauncher.jar'
$javac = Join-Path $PSScriptRoot 'build/toolchain/jdk-25.0.2+10/bin/javac.exe'
if (!(Test-Path -LiteralPath $javac)) { $javac = 'javac' }
& $javac -encoding UTF-8 -cp $jar -d $classes.FullName "$PSScriptRoot/tests/LauncherContractTest.java"
& "$image/runtime/bin/java.exe" -cp "$jar;$($classes.FullName)" LauncherContractTest

if ($Ui) {
    $originalLocalAppData = $env:LOCALAPPDATA
    try {
        $env:LOCALAPPDATA = Join-Path $classes.FullName 'test-user-data'
        & $javac -encoding UTF-8 -cp $jar -d $classes.FullName "$PSScriptRoot/tests/LauncherSettingsUiTest.java"
        & "$image/runtime/bin/java.exe" -cp "$jar;$($classes.FullName)" LauncherSettingsUiTest
    } finally {
        $env:LOCALAPPDATA = $originalLocalAppData
    }
}
