# ThorUtil

IME pin persistence, native mouse emulation and top display rotation control for the AYN Thor.

## Features

- **IME Pin Persist**: saves your chosen keyboard spawn screen (top/bottom) and restores it after sleep, reboot, or OdinSettings/BetterDualScreenAssistant resets
- **Native Mouse Mode**: OS-level pointer driven by left stick, right stick vertical and horizontal wheel scroll, with proper dead zone and no fake touch events
- Quick Settings tiles for mouse mode, IME pin and top display rotation
- **Top display rotation**: Auto, Portrait or Landscape via tile or app, plus an optional rotate button when the device is held the other way. Portrait works by holding a 1×1 overlay; it turns off Odin's Force Landscape while active and restores it on Auto
- **Odin shortcut clear**: one button empties Odin's gamepad-to-mouse shortcut so it can't fight the daemon, with restore
- Toggle mouse via QS tile or double-press Select
- A = left click, X = right, R3 = middle, B = back, Y = forward; L3 passes through
- Canvas workflow: hold R3 and move the left stick to middle-drag pan, right stick scrolls to zoom
- D-pad, shoulders, and Start pass through to apps
- Privilege via Shizuku, or root as a fallback; optional Magisk module starts the daemon at boot

## Requirements

- AYN Thor (Android 13+, arm64)
- Shizuku running, or Magisk root
- Odin mapping's own gamepad-to-mouse mode turned off while the daemon runs (use Clear Odin shortcut), and Pathfinder's Select double-press set to Nothing
- Display over other apps permission for rotation (the app grants it via Shizuku or root)

## Install

1. Install the APK from [Releases](https://github.com/popcat19/thorutil/releases) (Obtainium works with this repo URL)
2. Start Shizuku, open ThorUtil, tap Request Shizuku
3. Add the IME Pin, Mouse Emu and Rotation quick settings tiles
4. Optional: flash `thorutil-magisk.zip` in Magisk to start the daemon at boot

The daemon runs as the shell user and keeps its state in `/data/local/tmp/thorutil-mouse.state` (`<active> <pid>`). `kill -USR1 <pid>` toggles mouse mode. While `/data/local/tmp/thorutil-mouse.suspend` exists the pad is released and the hotkey is ignored; `kill -USR2 <pid>` makes the daemon re-read it. The app creates the file while the lockscreen is up ("Off on lockscreen", on by default).

## Build

The daemon is a static musl binary built with `zig cc`, so no NDK is needed. The flake provides SDK 35, JDK 17, Gradle and Zig:

```bash
nix develop
./gradlew assembleRelease :app:moduleZip
```

Outputs: `app/build/outputs/apk/release/app-release.apk` and `app/build/outputs/magisk/thorutil-magisk.zip`.

## Roadmap

Planned work and commit goals: [ROADMAP.md](ROADMAP.md).

## License

Apache-2.0
