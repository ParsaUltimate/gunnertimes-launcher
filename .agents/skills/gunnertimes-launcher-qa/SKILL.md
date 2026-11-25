---
name: gunnertimes-launcher-qa
description: Validate the Gunnertimes Swing launcher against its signed-pack contract, English and Persian layout, and portable Windows x64 package. Use when changing an install, launch, settings, localization, or release behavior in this repository.
---

# Objective

Verify the distributable under the bundled runtime and collect independent evidence for install, process start, menu, single-player, and official server join.

# Prerequisites

Use Windows x64 with JDK 25, PowerShell 7, and the local Gson dependency documented in the launcher README. Use a disposable profile and a process-local LOCALAPPDATA. A verified game and asset cache can shorten client boot checks. Record whether first-run downloads were fully cold or partly cached.

# Workflow

1. Build with `./launcher/build.ps1`. Pass its printed application-image directory to `./launcher/test.ps1 -ApplicationImage <directory>`. The test suite signs isolated fixtures with an ephemeral key and uses a loopback Minecraft status responder. Add `-Ui` on an interactive Windows desktop to test native tab traversal, persistence, draft edits, and invalid settings under a temporary LOCALAPPDATA.
2. Exercise the native executable from an extracted ZIP. The jpackage starter and its window-owning process can have different PIDs. Locate the window by the exact executable path rather than the first PID returned at startup.
3. Verify Home and all changed settings in both locales. Use process-local `-Dsun.java2d.uiScale=1.25` and `1.5` for Swing screenshot runs. Capture the real rendered window. At compact sizes, scroll the Home editorial panel below the hero and check for horizontally clipped controls.
4. Test the requested release action independently from Play. Record signed release version, payload hash observations and launcher phase events. Compare the installed pin and content before and after interrupted update or repair.
5. For gameplay, verify the rendered title screen and the integrated server login log for single-player. For Join, the client Quick Play log with the configured multiplayer address confirms login. A TCP or status ping, a started process, and an unconfirmed connection are separate outcomes.

# Validation

The controlled tests cover installation, pinned release, explicit update, interrupted transfer, confirmed repair preview, conflict preservation, Java validation, display settings, signed compatibility, player count, and unknown server status. Keep fixtures and bundled runtimes out of Git.

# Known Failure Modes

- Waiting for Windows Java to exit before reading its settings output can fill the process pipe. Drain bounded output concurrently while enforcing the probe deadline.
- HTTP or TCP failures do not establish server compatibility. A live server response is distinct from admitting the player.
- Swing's geometric focus traversal can jump out of mirrored navigation. The launcher uses container order so Home, Pack, Settings, and Help remain in logical sequence in both locales.
- Swing initially measures wrapped text before the final viewport width. Check the rendered screen after layout, especially in Persian, before claiming it fits.

# Recovery Procedures

Use the SHA-verified cache to separate stalled network transfers from launcher defects. Report cached boot evidence accurately. For changes to classpath or runtime discovery, rebuild and test with the packaged Java executable again.
