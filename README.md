# Gunnertimes Launcher

Windows x64 launcher for the official Gunnertimes pack, Minecraft 26.3 and Fabric Loader 0.20.1.

Get the two player downloads from the [public release channel](https://github.com/ParsaUltimate/gunnertimes-releases/releases/latest).

- **Gunnertimes-Setup.exe** installs for your Windows user and includes Java 25. Open Gunnertimes from Start or the desktop. To uninstall, select Gunnertimes in Windows Installed apps.
- **Gunnertimes-Portable.jar** requires an installed [Java 25 x64](https://learn.microsoft.com/en-us/java/openjdk/download). Put it in a writable folder and run `java -jar Gunnertimes-Portable.jar`.

Choose a profile and offline username under **Settings**. **Install** pins a signed release. **Play** verifies and opens it. **Join Gunnertimes** attempts to connect to the official server with the installed pack. **Update** previews and installs a newer pack. **Verify** inspects files and **Repair** previews changes before applying them to the installed release. Check for launcher updates under **Settings → Downloads and updates**. The launcher also checks on startup.

The default profile is `%LOCALAPPDATA%\Gunnertimes\profiles\Gunnertimes`. You can select a game directory or its `mods` folder. Assets, libraries and game versions are shared with `%APPDATA%\.minecraft`, while mods, config and saves stay in your profile. Keep independently launched Minecraft instances closed during pack maintenance. A verified installation can launch offline once the required files are present. Multiplayer servers must accept offline accounts.

## Build and test

Use Windows x64 and PowerShell 7. The build downloads and checksums pinned OpenJDK, Gson and WiX archives into the ignored `launcher/build/toolchain` directory. The native update helper uses the .NET Framework 4 compiler included with supported Windows. From a fresh checkout:

```powershell
./launcher/build.ps1
./launcher/test.ps1 -ApplicationImage '<path-to-built-Gunnertimes-directory>' -Ui
```

The build prints the paths to the installer and portable JAR. Use `-JarOnly` for the JAR, `-JdkHome` for a local JDK 25 x64, or `-Channel` for an isolated test pack channel. Run `-Ui` on an interactive Windows desktop. Both player builds use `launcher/resources/launcher-version.txt`.

## Release and diagnostics

1. Increment `launcher/resources/launcher-version.txt` and commit the source. Build both artifacts from that commit with the default toolchain. Preserve the installer upgrade UUID and signed update public key.
2. With Java 25 on the publishing workstation, compile the signing utility with `javac -encoding UTF-8 -cp "$jar" -d "$signingClasses" launcher/packaging/SignLauncherRelease.java`, where `$jar` is the new portable JAR and `$signingClasses` is an existing working directory. Write plain-text release notes to `$notes`, with the portable Java 25 x64 prerequisite.
3. Generate the signed body with `java -cp "$jar;$signingClasses" SignLauncherRelease sign "$artifactDir" "$privateKey" "$notes" "$signedBody"`. The private key is a PKCS#8 Ed25519 file stored outside the repository. The signer verifies it against the bundled public key. Preserve the signing key for future updates and keep a secured backup. A new, independent channel can bootstrap a key pair with `SignLauncherRelease generate-key PRIVATE_FILE PUBLIC_FILE` before its first build.
4. Create a draft in `ParsaUltimate/gunnertimes-releases` with tag `launcher-vX.Y.Z` using `gh release create`, `--notes-file "$signedBody"` and the two player assets. Check file sizes and names, then publish as stable with `gh release edit launcher-vX.Y.Z --draft=false --latest`. Link the private source revision and [matching OpenJDK source](https://github.com/microsoft/openjdk-jdk25u/tree/release/jdk-25.0.2_10) in the notes.
5. Confirm the GitHub release is neither a draft nor a prerelease, and both asset URLs work without authentication. In the public releases repository, commit the same signed body as `launcher-stable.md` on `main`. The client reads that signed file anonymously, then downloads the matching artifact from GitHub Releases. Leave this file untouched for prereleases. Only the publisher needs GitHub credentials.

Installed release state is pinned by `.gunnertimes/signed-release.json`. Updates commit it in the same transaction as payloads and ownership. Repair replaces damaged owned files only after confirmation and retains their originals in the transaction backup. Treat versions as immutable and increment the version for each pack change.

Home reads release notes from a published release tag matching the signed pack version in the official release repository. An optional signed manifest property `serverCompatibility` can contain the official `address`, an ISO 8601 `expires` timestamp and a non-empty `allowedPackVersions` array. Only a fresh, valid signed declaration for the configured server can block a Join. Ordinary server status responses supply online status and an optional player count.

Launcher and Minecraft logs are in the selected profile's `logs` folder. The Settings diagnostic clipboard summary omits the username, paths and raw logs. The storage page measures categories without deleting player files or offline cache data.

## Design references

[Gunnertimes pack releases](https://github.com/ParsaUltimate/gunnertimes-releases) · [Lunar launcher](https://www.lunarclient.com/news/the-new-launcher-is-here) · [Lunar settings](https://www.lunarclient.com/news/lunar-client-monthly-recap-14-may-2025) · [XMCL launch implementation](https://github.com/Voxelum/minecraft-launcher-core-node/blob/master/packages/core/launch.ts)

Persian UI typography uses the official Dastkhat Main static Regular, Medium and SemiBold files. The TTFs load directly from the application JAR. The package includes the OFL notice and pinned source in `licenses/Dastkhat`. [Dastkhat v1.220 source](https://github.com/dastyar-io/dastkhat-font/tree/19f4a12b56a89567b07e02500f0e4aed670e3644)
