# Gunnertimes Launcher

Portable Windows x64 launcher for the official Gunnertimes pack, Minecraft 26.3, Fabric Loader 0.20.1 and Java 25.

Extract the complete ZIP and run `Gunnertimes.exe`. Choose a profile and offline username under **Settings**. **Install** pins a signed release. **Play** verifies and opens that installed release. **Join Gunnertimes** attempts the official server with the same release. **Update** previews a newer release and applies it after confirmation. The **Pack** tab lists signed mods, verifies them without writing, and previews repair for the installed release.

The default profile is `%LOCALAPPDATA%\Gunnertimes\profiles\Gunnertimes`. Accepts the game directory or its `mods` folder. Keep an independently launched Minecraft instance closed during pack updates. The launcher can reuse cached game files offline. Online-mode servers may require a type of account this offline launcher does not provide.

## Build and test

Use PowerShell 7, Windows x64 JDK 25 including `jpackage`, and Gson 2.14.0 in the Minecraft libraries directory. From the repository root:

```powershell
./launcher/build.ps1
./launcher/test.ps1 -ApplicationImage <path-to-built-Gunnertimes-directory>
```

The build prints the EXE and portable ZIP. `-Libraries` overrides the library directory and `-Channel` selects a trusted channel file. The repository includes the public channel endpoint and verification key. It does not contain the signing key, Minecraft payloads or configuration from player profiles.

## Release and diagnostics

Installed release state is pinned by `.gunnertimes/signed-release.json`. Updates commit it in the same transaction as payloads and ownership. Repair replaces damaged owned files only after confirmation and retains their originals in the transaction backup. Treat versions as immutable and increment the version for each pack change.

Home reads release notes from a published release tag matching the signed pack version in the official release repository. An optional signed manifest property `serverCompatibility` can contain the official `address`, an ISO 8601 `expires` timestamp and a non-empty `allowedPackVersions` array. Only a fresh, valid signed declaration for the configured server can block a Join. Ordinary server status responses supply online status and an optional player count.

Launcher and Minecraft logs are in the selected profile's `logs` folder. The Settings diagnostic clipboard summary omits the username, paths and raw logs. The storage page measures categories without deleting player files or offline cache data.

## Design references

[Gunnertimes pack releases](https://github.com/ParsaUltimate/gunnertimes-releases) · [Lunar launcher](https://www.lunarclient.com/news/the-new-launcher-is-here) · [Lunar settings](https://www.lunarclient.com/news/lunar-client-monthly-recap-14-may-2025) · [XMCL launch implementation](https://github.com/Voxelum/minecraft-launcher-core-node/blob/master/packages/core/launch.ts)
