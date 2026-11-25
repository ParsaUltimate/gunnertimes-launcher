param([Parameter(Mandatory)][string]$ApplicationImage)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$image = (Resolve-Path -LiteralPath $ApplicationImage).Path
$classes = New-Item -ItemType Directory -Path (Join-Path $env:TEMP ("gunnertimes-test-" + [Guid]::NewGuid()))
$jar = Join-Path $image 'app/GunnertimesLauncher.jar'
& javac -encoding UTF-8 -cp $jar -d $classes.FullName "$PSScriptRoot/tests/LauncherContractTest.java"
& "$image/runtime/bin/java.exe" -cp "$jar;$($classes.FullName)" LauncherContractTest
