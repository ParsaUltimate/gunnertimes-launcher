param(
    [string]$Channel = "$PSScriptRoot/resources/gunnertimes-channel.json",
    [string]$Version = (Get-Content -Raw "$PSScriptRoot/resources/launcher-version.txt").Trim(),
    [switch]$JarOnly,
    [string]$JdkHome
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$ProgressPreference = 'SilentlyContinue'
if ($Version -notmatch '^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$') { throw 'Use a stable numeric version.' }
if (!$IsWindows -or [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') {
    throw 'Build on Windows x64 with PowerShell 7.'
}
$work = New-Item -ItemType Directory -Force (Join-Path $PSScriptRoot 'build')
$tools = New-Item -ItemType Directory -Force (Join-Path $work 'toolchain')

function Get-Dependency($Url, $Name, $Sha256) {
    $target = Join-Path $tools $Name
    if (!(Test-Path -LiteralPath $target)) {
        $pending = Join-Path $tools ([Guid]::NewGuid().ToString('N') + '.download')
        try {
            Invoke-WebRequest $Url -OutFile $pending
            if ((Get-FileHash -LiteralPath $pending).Hash -ne $Sha256) { throw "Checksum mismatch for $Name" }
            Move-Item -LiteralPath $pending -Destination $target
        } finally {
            if (Test-Path -LiteralPath $pending) { Remove-Item -LiteralPath $pending }
        }
    }
    if ((Get-FileHash -LiteralPath $target).Hash -ne $Sha256) { throw "Checksum mismatch for $target" }
    return $target
}

$gson = Get-Dependency 'https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar' `
    'gson-2.14.0.jar' '2cbd119bf1961c28788310963dc80ba65f58cdeec1dd139c8bdb1240faa2c36f'
$jdk = $JdkHome
if (!$jdk) {
    $jdkArchive = Get-Dependency 'https://aka.ms/download-jdk/microsoft-jdk-25.0.2-windows-x64.zip' `
        'microsoft-jdk.zip' '38d1a42d189c50b24152014ef131931f25f4cc80400ce618f0477f5e4e5aa252'
    $jdk = Join-Path $tools 'jdk-25.0.2+10'
    if (!(Test-Path "$jdk/bin/jpackage.exe")) { Expand-Archive -LiteralPath $jdkArchive -DestinationPath $tools.FullName -Force }
}
$jdkRelease = Get-Content -Raw -LiteralPath (Join-Path $jdk 'release')
if ($jdkRelease -notmatch '(?m)^JAVA_VERSION="25[."]' -or $jdkRelease -notmatch '(?m)^OS_ARCH="(x86_64|amd64)"') {
    throw 'The build requires a Windows x64 JDK 25.'
}
$wix = Join-Path $tools 'wix'
if (!$JarOnly) {
    $wixArchive = Get-Dependency 'https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip' `
        'wix.zip' '6AC824E1642D6F7277D0ED7EA09411A508F6116BA6FAE0AA5F2C7DAA2FF43D31'
    if (!(Test-Path "$wix/light.exe")) { Expand-Archive -LiteralPath $wixArchive -DestinationPath $wix -Force }
}
$stage = New-Item -ItemType Directory (Join-Path $work ([Guid]::NewGuid().ToString('N')))
$classes = New-Item -ItemType Directory (Join-Path $stage 'classes')
$sourceFiles = @(Get-ChildItem (Join-Path $PSScriptRoot 'src') -Filter '*.java' -File | ForEach-Object FullName)
& "$jdk/bin/javac.exe" --release 25 -encoding UTF-8 -cp $gson -d $classes.FullName `
    "$PSScriptRoot/../updater/src/Updater.java" @sourceFiles
Get-ChildItem (Join-Path $PSScriptRoot 'resources') | Copy-Item -Recurse -Destination $classes.FullName
Copy-Item -LiteralPath $Channel -Destination (Join-Path $classes 'gunnertimes-channel.json')
Set-Content -LiteralPath (Join-Path $classes 'launcher-version.txt') -Value $Version -Encoding ascii
$csc = Join-Path $env:SystemRoot 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
$helperExe = Join-Path $classes 'UpdateHelper.exe'
$helperSource = Join-Path $PSScriptRoot 'packaging\UpdateHelper.cs'
& $csc /nologo /target:winexe /platform:x64 /codepage:65001 /reference:System.Windows.Forms.dll `
    "/out:$helperExe" $helperSource
Push-Location $classes
try {
    & "$jdk/bin/jar.exe" xf $gson com/google/gson META-INF/LICENSE META-INF/NOTICE
} finally {
    Pop-Location
}
$portable = Join-Path $stage 'Gunnertimes-Portable.jar'
& "$jdk/bin/jar.exe" --create --file $portable --main-class GunnertimesLauncher -C $classes.FullName .
if (!$JarOnly) {
    $inputFiles = New-Item -ItemType Directory (Join-Path $stage 'input')
    Copy-Item -LiteralPath $portable -Destination (Join-Path $inputFiles 'GunnertimesLauncher.jar')
    & "$jdk/bin/jpackage.exe" --type app-image --name 'Gunnertimes' --app-version $Version --vendor 'Gunnertimes' `
        --input $inputFiles.FullName --main-jar GunnertimesLauncher.jar --dest $stage.FullName `
        --icon "$PSScriptRoot/packaging/gunnertimes.ico" `
        --java-options '-Dgunnertimes.installed=true' `
        --add-modules 'java.se,jdk.unsupported,jdk.crypto.ec,jdk.management,jdk.zipfs,jdk.charsets,jdk.localedata,jdk.jfr,jdk.management.jfr' `
        --jlink-options '--strip-debug --no-man-pages --no-header-files'
    $licenses = New-Item -ItemType Directory -Path (Join-Path $stage 'Gunnertimes/licenses/Dastkhat') -Force
    Copy-Item -LiteralPath "$PSScriptRoot/resources/dastkhat/OFL.txt", "$PSScriptRoot/resources/dastkhat/UPSTREAM.txt" -Destination $licenses.FullName
    $oldPath = $env:PATH
    try {
        $env:PATH = "$wix;$oldPath"
        & "$jdk/bin/jpackage.exe" --type msi --name 'Gunnertimes' --app-version $Version --vendor 'Gunnertimes' `
            --app-image "$($stage.FullName)/Gunnertimes" --dest $stage.FullName --install-dir 'Gunnertimes Launcher' `
            --win-per-user-install --win-menu --win-menu-group 'Gunnertimes' --win-shortcut `
            --win-upgrade-uuid 'ec49f39f-8138-4f13-8cd2-ffdc70accc99'
    } finally { $env:PATH = $oldPath }
    $msi = Join-Path $stage "Gunnertimes-$Version.msi"
    $setup = Join-Path $stage 'Gunnertimes-Setup.exe'
    $setupSource = Join-Path $PSScriptRoot 'packaging\Setup.cs'
    $setupIcon = Join-Path $PSScriptRoot 'packaging\gunnertimes.ico'
    & $csc /nologo /target:winexe /platform:x64 /reference:System.Windows.Forms.dll `
        "/out:$setup" "/win32icon:$setupIcon" "/resource:$msi,Gunnertimes.msi" $setupSource
    Write-Output $setup
}
Write-Output $portable
