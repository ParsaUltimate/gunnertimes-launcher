param(
    [Parameter(Mandatory)][string]$Evidence,
    [Parameter(Mandatory)][string]$Jdk,
    [switch]$Installed,
    [string]$OlderVersion = '1.4.1',
    [string]$ExpectedVersion = '1.4.5',
    [string]$OlderSetup
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$Evidence = (New-Item -ItemType Directory -Force $Evidence).FullName
$fixture = (New-Item -ItemType Directory (Join-Path $Evidence 'fixture')).FullName
$jdkBin = Join-Path (Resolve-Path $Jdk).Path 'bin'

function Await($description, [scriptblock]$check, $seconds = 100) {
    $end = (Get-Date).AddSeconds($seconds)
    do {
        if (& $check) { return }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $end)
    throw "Timed out: $description"
}
function Action($command, $seconds = 100) {
    $name = Join-Path $Evidence 'command.txt'
    Set-Content -LiteralPath "$name.pending" -Value $command -NoNewline
    Move-Item -LiteralPath "$name.pending" -Destination $name
    Await "visible action $command" { !(Test-Path -LiteralPath $name) } $seconds
}
function Download($version, $filename, $destination) {
    $url = "https://github.com/ParsaUltimate/gunnertimes-releases/releases/download/launcher-v$version/$filename"
    curl.exe -fLsS --retry 2 --max-time 180 --output $destination $url
}
function RelatedProducts {
    $records = New-Object -ComObject WindowsInstaller.Installer
    foreach ($code in $records.RelatedProducts('{EC49F39F-8138-4F13-8CD2-FFDC70ACCC99}')) {
        [pscustomobject]@{
            Version = $records.ProductInfo($code,'VersionString')
            Path = $records.ProductInfo($code,'InstallLocation')
        }
    }
}
if ($Installed -and @(RelatedProducts).Count) {
    throw 'Use a test Windows account without an existing Gunnertimes installation.'
}
$classes = (New-Item -ItemType Directory (Join-Path $fixture 'classes')).FullName
& "$jdkBin/javac.exe" -encoding UTF-8 -d $classes "$PSScriptRoot/tests/LauncherUpgradeAgent.java"
$agent = Join-Path $fixture 'agent.jar'
$manifest = Join-Path $fixture 'agent.mf'
'Premain-Class: LauncherUpgradeAgent' | Set-Content -LiteralPath $manifest -Encoding ascii
& "$jdkBin/jar.exe" --create --file $agent --manifest $manifest -C $classes .
$profile = (New-Item -ItemType Directory (Join-Path $fixture 'Player profile فارسی')).FullName
$oldLocal = $env:LOCALAPPDATA
$oldRoaming = $env:APPDATA
$oldJava = $env:JAVA_TOOL_OPTIONS
$env:LOCALAPPDATA = Join-Path $fixture 'Local'
$env:APPDATA = Join-Path $fixture 'Roaming'
$upgradeData = (New-Item -ItemType Directory -Force (Join-Path $env:LOCALAPPDATA 'Gunnertimes')).FullName
$prefs = Join-Path $upgradeData 'launcher.json'
@{username='UpgradeQA';profile=$profile;language='en';ramGiB=5} | ConvertTo-Json | Set-Content -LiteralPath $prefs
$keep = @($prefs)
foreach ($relative in @('saves/World/level.dat','config/settings.txt','mods/manual.jar')) {
    $file = Join-Path $profile $relative
    New-Item -ItemType Directory -Force (Split-Path -Parent $file) | Out-Null
    Set-Content -LiteralPath $file -Value "Protected test file $relative"
    $keep += $file
}
$before = @($keep | ForEach-Object { (Get-FileHash -LiteralPath $_).Hash })
try {
    if ($Installed) {
        $setup = Join-Path $fixture 'old-setup.exe'
        if ($OlderSetup) { Copy-Item -LiteralPath $OlderSetup -Destination $setup }
        else { Download $OlderVersion 'Gunnertimes-Setup.exe' $setup }
        $process = Start-Process $setup -ArgumentList '/quiet','/norestart' -PassThru -WindowStyle Hidden
        Await 'baseline installer process' { $process.HasExited }
        $previous = RelatedProducts
        if ($process.ExitCode -ne 0 -or $previous.Version -ne $OlderVersion) {
            throw "Baseline installer $OlderVersion failed. Exit code $($process.ExitCode), registered version $($previous.Version)."
        }
        $target = Join-Path $previous.Path 'Gunnertimes.exe'
        $jar = Join-Path $previous.Path 'app/GunnertimesLauncher.jar'
    } else {
        $portable = (New-Item -ItemType Directory (Join-Path $fixture 'Portable launcher فارسی')).FullName
        $jar = Join-Path $portable 'Gunnertimes-Portable.jar'
        Download $OlderVersion 'Gunnertimes-Portable.jar' $jar
    }
    $mode = if ($Installed) {'installed'} else {'portable'}
    $initialHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA512).Hash
    $env:JAVA_TOOL_OPTIONS = '"-javaagent:' + $agent + '=' + $Evidence + '"'
    if ($Installed) { Start-Process $target -WindowStyle Hidden }
    else { Start-Process "$jdkBin/javaw.exe" -ArgumentList ('-jar "' + $jar + '"') -WindowStyle Hidden }
    $oldRunning = Join-Path $Evidence "running-$OlderVersion"
    Await 'old launcher window' { Test-Path $oldRunning }
    if ((Get-Content $oldRunning) -notlike "$mode *") { throw 'Incorrect distribution type.' }
    $previousPid = [int]((Get-Content $oldRunning).Split(' ')[1])
    Action 'dialog|Later|01-startup-offer'
    if ((Get-FileHash -LiteralPath $jar -Algorithm SHA512).Hash -ne $initialHash) { throw 'Later changed the launcher.' }
    Action 'frame|Settings'
    $manual = if ([version]$OlderVersion -ge [version]'1.4.4') {'About'} else {'Downloads & updates'}
    Action "frame|$manual"
    Action 'frame|Check for launcher updates'
    Action 'dialog|Update and restart|02-manual-offer'
    Action 'dialog|Cancel|03-interrupted-download'
    if ((Get-FileHash -LiteralPath $jar -Algorithm SHA512).Hash -ne $initialHash) { throw 'Cancel changed the launcher.' }
    Action 'frame|Check for launcher updates'
    if ($Installed) {
        Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class UpgradeDialog {
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hwnd, uint message, IntPtr wparam, IntPtr lparam);
    [DllImport("user32.dll")] public static extern IntPtr GetDlgItem(IntPtr hwnd, int id);
}
'@
        $updates = Join-Path $upgradeData 'launcher-updates'
        $lockFile = Join-Path $previous.Path 'qa-locked-file'
        $lock = [IO.File]::Open($lockFile, 'CreateNew', 'ReadWrite', 'None')
        try {
            Action 'dialog|Update and restart|04-locked-attempt'
            Await 'helper reported the locked destination' {
                Get-ChildItem -LiteralPath $updates -Filter '*.failure.log' -ErrorAction SilentlyContinue
            } 160
            $failureLog = Get-ChildItem -LiteralPath $updates -Filter '*.failure.log' | Select-Object -First 1
            if ((Get-Content -LiteralPath $failureLog.FullName -Raw) -notmatch 'qa-locked-file') {
                throw 'Updater failed for a reason other than the locked file.'
            }
            Copy-Item -LiteralPath $failureLog.FullName -Destination (Join-Path $Evidence 'locked-destination.txt')
        } finally {
            $lock.Dispose()
            Remove-Item -LiteralPath $lockFile
        }
        Await 'dismiss native helper error dialog' {
            $helper = Get-Process -Name 'helper-*' -ErrorAction SilentlyContinue |
                Where-Object { $_.Path -like "$updates*" -and $_.MainWindowHandle } | Select-Object -First 1
            if ($helper) {
                $dialog = $helper.MainWindowHandle
                $ok = [UpgradeDialog]::GetDlgItem($dialog, 1)
                if ($ok -ne [IntPtr]::Zero) {
                    [UpgradeDialog]::PostMessage($dialog, 0x111, [IntPtr]1, $ok)
                }
            }
        }
        Await 'previous version reopened' {
            (Get-Content -LiteralPath $oldRunning -Raw) -ne "$mode $previousPid"
        } 30
        if ((Get-FileHash -LiteralPath $jar -Algorithm SHA512).Hash -ne $initialHash) { throw 'Locked update changed the launcher.' }
    }
    Action 'dialog|Update and restart|05-retry'
    $newRunning = Join-Path $Evidence "running-$ExpectedVersion"
    Await 'updated launcher to reopen' { Test-Path -LiteralPath $newRunning } 220
    $newState = Get-Content $newRunning
    if ($newState -notlike "$mode *" -or [int]($newState.Split(' ')[1]) -eq $previousPid) {
        throw 'Updated launcher did not restart as the original distribution type.'
    }
    if ($Installed) {
        $current = RelatedProducts
        if ($current.Version -ne $ExpectedVersion -or $current.Path -ne $previous.Path) {
            throw 'MSI version or installation location differs.'
        }
        $shell = New-Object -ComObject WScript.Shell
        $links = @(foreach ($folder in @($shell.SpecialFolders.Item('Programs'),$shell.SpecialFolders.Item('Desktop'))) {
            if (Test-Path $folder) { Get-ChildItem -LiteralPath $folder -Filter '*.lnk' -Recurse }
        })
        if (!($links | Where-Object { $shell.CreateShortcut($_.FullName).TargetPath -eq $target })) {
            throw 'No shortcut to the upgraded launcher.'
        }
    }
    $publicJar = Join-Path $fixture 'published.jar'
    Download $ExpectedVersion 'Gunnertimes-Portable.jar' $publicJar
    if ((Get-FileHash -LiteralPath $jar -Algorithm SHA512).Hash -ne
        (Get-FileHash -LiteralPath $publicJar -Algorithm SHA512).Hash) { throw 'Updated JAR does not match public artifact.' }
    $after = @($keep | ForEach-Object { (Get-FileHash -LiteralPath $_).Hash })
    if (Compare-Object $before $after -SyncWindow 0) { throw 'Preferences or profile files changed.' }
    Action 'frame|Settings'
    Action 'frame|About'
    Action 'frame|Check for launcher updates|06-updated-about'
    Action 'exit|now'
    "PASS: $mode $OlderVersion -> $ExpectedVersion. Later, cancelled download, retry, restart, version and preserved player files." |
        Tee-Object -FilePath (Join-Path $Evidence 'result.txt')
} finally {
    $env:LOCALAPPDATA = $oldLocal
    $env:APPDATA = $oldRoaming
    $env:JAVA_TOOL_OPTIONS = $oldJava
}
