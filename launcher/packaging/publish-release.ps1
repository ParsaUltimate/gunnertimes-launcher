param(
    [Parameter(Mandatory)][string]$Stage,
    [Parameter(Mandatory)][ValidatePattern('^launcher-v[0-9]+\.[0-9]+\.[0-9]+$')][string]$Tag
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$repo = 'ParsaUltimate/gunnertimes-releases'
if (!$env:GH_TOKEN -or !$env:LAUNCHER_SIGNING_KEY) {
    throw 'Set LAUNCHER_RELEASE_TOKEN and LAUNCHER_SIGNING_KEY before publishing.'
}
$jdk = Join-Path $PSScriptRoot '../build/toolchain/jdk-25.0.2+10'
$jar = Join-Path $Stage 'Gunnertimes-Portable.jar'
$setup = Join-Path $Stage 'Gunnertimes-Setup.exe'
$files = Get-Item -LiteralPath $setup, $jar
$version = $Tag.Substring('launcher-v'.Length)
$stablePath = "repos/$repo/contents/launcher-stable.md"
$stable = gh api $stablePath | ConvertFrom-Json
$current = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($stable.content))
if ($current -notmatch '<!-- gunnertimes-update-v1 ([A-Za-z0-9+/=]+) ') { throw 'Invalid existing stable channel.' }
$existing = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Matches[1])) | ConvertFrom-Json
if ([version]$existing.version -ge [version]$version) { throw 'The stable channel already has this version or a newer one.' }

$notes = Join-Path $Stage 'notes.md'
$signed = Join-Path $Stage 'signed-body.md'
@"
Windows x64. Setup includes Java 25. The Portable JAR requires Java 25 x64.

Launcher source revision: $env:GITHUB_SHA.
[Bundled OpenJDK source](https://github.com/microsoft/openjdk-jdk25u/tree/release/jdk-25.0.2_10)
"@ | Set-Content -LiteralPath $notes -Encoding utf8
$classes = New-Item -ItemType Directory -Force (Join-Path $Stage 'signing-classes')
$key = Join-Path $env:RUNNER_TEMP ("launcher-signing-" + [guid]::NewGuid() + ".pk8")
try {
    [IO.File]::WriteAllBytes($key, [Convert]::FromBase64String($env:LAUNCHER_SIGNING_KEY))
    Remove-Item Env:LAUNCHER_SIGNING_KEY
    & "$jdk/bin/javac.exe" -encoding UTF-8 -cp $jar -d $classes.FullName "$PSScriptRoot/SignLauncherRelease.java"
    & "$jdk/bin/java.exe" -cp "$jar;$($classes.FullName)" SignLauncherRelease sign $Stage $key $notes $signed
} finally {
    if (Test-Path -LiteralPath $key) { Remove-Item -LiteralPath $key }
}

$prior = gh api --paginate "repos/$repo/releases?per_page=100" --jq ".[] | select(.tag_name == `"$Tag`") | {isDraft: .draft}" | ConvertFrom-Json
if ($prior) {
    if (!$prior.isDraft) { throw 'Release already published. Verify its existing signed assets before advancing the channel.' }
    gh release edit $Tag --repo $repo --notes-file $signed
    gh release upload $Tag $setup $jar --repo $repo --clobber
} else {
    gh release create $Tag $setup $jar --repo $repo --title "Gunnertimes Launcher $version" --draft --notes-file $signed
}

$release = gh release view $Tag --repo $repo --json assets,isDraft,isPrerelease | ConvertFrom-Json
if (!$release.isDraft -or $release.isPrerelease -or $release.assets.Count -ne $files.Count) { throw 'Unexpected release state.' }
foreach ($file in $files) {
    $asset = @($release.assets | Where-Object name -CEQ $file.Name)
    if ($asset.Count -ne 1 -or $asset[0].state -ne 'uploaded' -or $asset[0].size -ne $file.Length) {
        throw "Incorrect uploaded asset $($file.Name)."
    }
}
gh release edit $Tag --repo $repo --draft=false --latest=false
foreach ($file in $files) {
    $download = Join-Path $Stage ("public-" + $file.Name)
    curl.exe --fail --silent --show-error --location --retry 5 --output $download "https://github.com/$repo/releases/download/$Tag/$($file.Name)"
    if ((Get-FileHash $download -Algorithm SHA512).Hash -ne (Get-FileHash $file.FullName -Algorithm SHA512).Hash) {
        throw "Public download failed verification for $($file.Name)."
    }
}
$channel = Join-Path $Stage 'publish-channel.json'
@{message="Publish signed launcher $version stable channel"; branch='main'; sha=$stable.sha;
    content=[Convert]::ToBase64String([IO.File]::ReadAllBytes($signed))} |
    ConvertTo-Json -Compress | Set-Content -LiteralPath $channel -Encoding utf8
gh api --method PUT $stablePath --input $channel --jq .commit.sha
gh release edit $Tag --repo $repo --latest
