# Gunnertimes Launcher

Gunnertimes Launcher is a portable Windows x64 application for Minecraft 26.3 and Fabric Loader 0.20.1. Extract the complete ZIP and open `Gunnertimes.exe` inside the extracted folder.

- **Play** shows the profile and tracks file checks, downloads and verification, installation, and the Minecraft process. Session details can be copied. Open the session or client log from the same screen.
- **Settings** manages the offline username, profile folder, display language, and game memory. English is the default, with a fully right-to-left Persian interface available from any screen.
- **Help** explains the first launch, profile paths, recovery after an interrupted download, and where to find logs.

The default profile is `%LOCALAPPDATA%\Gunnertimes\profiles\Gunnertimes`. Select a profile's game directory or its `mods` folder. Close Minecraft before reusing a profile that was opened through another launcher. The first run needs internet for missing files. If the release channel is unavailable, an already verified profile can use its cached release. Multiplayer requires a server that accepts offline accounts.

Java 25 is included. The launcher can also use compatible Java 25 x64 from `JAVA_HOME` or `PATH`. Shared game files live in `%LOCALAPPDATA%\Gunnertimes\game`. Session and client logs live in the selected profile's `logs` directory.

## Build

Use a Windows x64 JDK 25 with `jpackage`, PowerShell 7, and Gson 2.14.0 from the Minecraft libraries directory. Run `./launcher/build.ps1` from the repository root. The default channel comes from the sibling `gunnertimes-releases` checkout. Use `-Channel` to select an alternate trusted channel. The script prints the application image executable and its distributable ZIP.
