# ThorUtil

IME pin persistence, native mouse emulation and top display rotation control for the AYN Thor.

## Features

- **IME Pin Persist**: saves your chosen keyboard spawn screen (top/bottom) and restores it after sleep, reboot, or OdinSettings/BetterDualScreenAssistant resets
- **Native Mouse Mode**: OS-level pointer driven by left stick, right stick vertical and horizontal wheel scroll, with proper dead zone and no fake touch events
- Quick Settings tiles for mouse mode, IME pin and top display rotation
- **Top display rotation**: Auto, Portrait or Landscape via tile or app, plus an optional rotate button when the device is held the other way. Portrait works by holding a 1×1 overlay; it turns off Odin's Force Landscape while active and restores it on Auto
- **Odin shortcut clear**: one button empties Odin's gamepad-to-mouse shortcut so it can't fight the daemon, with restore
- Toggle mouse via QS tile or double-press Select
- A = left click, X = right, R3 = middle, B = back, Y = forward; L3 moves the pointer between screens
- Canvas workflow: hold R3 and move the left stick to middle-drag pan, right stick scrolls to zoom
- D-pad, shoulders, and Start pass through to apps
- **AYN combos** (accessibility service): AYN+L1/R1 focus top/bottom, AYN+Select swap focus, AYN+Start swap windows, AYN+B toggle recents (top), AYN+X mouse mode. Lone AYN tap still opens the AYN menu
- **Pad combos**: Home+L1/R1 home on top/bottom, Back+L1/R1 previous app on top/bottom. Lone Back goes to the touched or focused screen, lone Home to the focused screen
- Mouse mode starts on the last touched or focused screen
- Toast and haptic feedback per action type: bip = on/top/auto, brt = off/bottom/fixed
- **Bottom volume link**: optional, syncs the bottom speaker volume (`secondary_screen_volume_level`) to media volume; replaces the thorvolume app
- **Smoothed fan curve**: optional native daemon (`libthorfan.so`) driving the fan from board soak (`xo-therm`) with averaging, hysteresis, slew limits and a sustained junction guard; replaces Odin Smart and yields when you pick Quiet, Sport or Off
- Privilege via Shizuku, or root as a fallback; the daemons start whenever Shizuku comes up

## Requirements

- AYN Thor (Android 13+, arm64)
- Shizuku 13.6+ running, or root
- Odin mapping's own gamepad-to-mouse mode turned off while the daemon runs (use Clear Odin shortcut), Pathfinder uninstalled or its AYN and Select-double binds cleared
- Display over other apps permission for rotation (the app grants it via Shizuku or root)

## Install

1. Install the APK from [Releases](https://github.com/popcat19/thorutil/releases) (Obtainium works with this repo URL)
2. Start Shizuku, open ThorUtil, tap Request Shizuku
3. Add the IME Pin, Mouse Emu and Rotation quick settings tiles
4. For boot start, turn on Shizuku "Start on boot" (works without root on a trusted Wi-Fi) and ThorUtil "Start daemon with Shizuku"

From 0.4.0 releases use a release key. Updating from 0.3.x needs one uninstall and reinstall. Later updates install in place; use config export to keep settings across reinstalls.

Release cert SHA-256: `31bbf3ecf29910736b3e7c65cc16cb8b47f24c6d92044a358dff439a56cd93f2`

The daemon runs as the shell user and keeps its state in `/data/local/tmp/thorutil-mouse.state` (`<active> <pid>`). `kill -USR1 <pid>` toggles mouse mode. While `/data/local/tmp/thorutil-mouse.suspend` exists the pad is released and the hotkey is ignored; `kill -USR2 <pid>` makes the daemon re-read it. The app creates the file while the lockscreen is up ("Off on lockscreen", on by default).

## Build

The daemon is a static musl binary built with `zig cc`, so no NDK is needed. The flake provides SDK 35, JDK 17, Gradle and Zig:

```bash
nix develop
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`.

Release signing reads `~/key/thorutil-release.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without it the debug key is used.

## Roadmap

Planned work and commit goals: [ROADMAP.md](ROADMAP.md).

## License

Apache-2.0
