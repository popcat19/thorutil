# Roadmap

## v0.3.0

- [ ] `feat(daemon): lower default speeds to 1000 px/s and 12 detents/s`
- [ ] `feat(daemon): remap mouse buttons for xbox layout`
  - A → left, X → right, R3 → middle, B → back, Y → forward. L3 passes through.
  - Middle-drag pan: R3 + left stick.
- [ ] `feat(daemon): toggle mouse mode on double-press select`
  - 300 ms window. Replaces Select+Start hold.
- [ ] `feat(app): suspend mouse emu while keyguard is shown`
  - Release grab on lock, restore on USER_PRESENT.
- [ ] `feat(app): add button to clear odin mouse-mode shortcut`
  - Clears `first/second_gamepad_to_mouse_key`, sets `global_gamepad_to_mouse_mode=0`.
  - Pathfinder Select-double must be set to NOTHING manually.
- [ ] `feat(app): add rotation suggestion button for top display`
- [ ] `feat(app): add rotation tile`
  - Top display: Auto → Portrait → Landscape.
- [ ] `chore: bump version to 0.3.0`

## v0.4.0

- [ ] `feat(daemon): use ayn button as meta modifier`
  - `gpio-keys` code 194.
- [ ] `feat(app): add swap screens action`
- [ ] `feat(app): link bottom screen volume to media volume`
- [ ] `feat(app): switch to previous app on focused screen`
- [ ] `build: sign release with local keystore`
  - Secrets via `local.properties` or env.
- [ ] `chore: bump version to 0.4.0`

## v0.5.0

- [ ] Check for existing community app first.
- [ ] `feat(app): add per-screen recents overlay`
  - Gamepad list on focused display: open, lock, app info, clear all, move screen.
  - Deferred: split screen, floating.
- [ ] `chore: bump version to 0.5.0`

## Dropped

- Bluetooth tile: stock `bt` exists.

## Notes

- Odin emu bind: `first_gamepad_to_mouse_key=START`, `second_gamepad_to_mouse_key=UP`.
- Pathfinder defaults: Back double → RECENTS, Back hold → SWAP_SCREENS, Select double → MOUSE_MODE.
- Displays: 0 = top 1080×1920, 4 = bottom 1080×1240.
