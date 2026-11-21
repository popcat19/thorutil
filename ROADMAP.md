# Roadmap

## v0.3.0

- [x] `feat(daemon): lower default speeds to 1000 px/s and 12 detents/s`
- [x] `feat(daemon): remap mouse buttons for xbox layout`
  - A → left, X → right, R3 → middle, B → back, Y → forward. L3 moves the pointer between screens.
  - Middle-drag pan: R3 + left stick.
- [x] `feat(daemon): toggle mouse mode on double-press select`
  - 300 ms window. Replaces Select+Start hold.
- [x] `feat(app): suspend mouse emu while keyguard is shown`
  - Release grab on lock, restore on USER_PRESENT.
- [x] `feat(app): add button to clear odin mouse-mode shortcut`
  - Clears `first/second_gamepad_to_mouse_key`, sets `global_gamepad_to_mouse_mode=0`.
  - Pathfinder Select-double must be set to NOTHING manually.
- [x] `feat(app): add rotation suggestion button for top display`
- [x] `feat(app): add rotation tile`
  - Top display: Auto → Portrait → Landscape.
- [x] `chore: bump version to 0.3.0`

## v0.3.1

- [x] `feat(daemon): lower default deadzone to 0.02 for hall sticks`
- [x] `feat(app): add input field and steps to tuning sliders`
- [x] `feat(app): add per-panel density control with presets`
- [x] `chore: bump version to 0.3.1`

## v0.4.0

- [x] `feat(app): add accessibility key filter for ayn combos`
  - AYN = scan 194 on `gpio-keys`; framework turns it into `action.tcc.button.key.event` (AYN menu).
  - Lone tap re-sent via uinput `gpio-keys` clone (shell uid, Shizuku ok); filter skips own device by vendor id.
  - Pathfinder AYN binds must be cleared manually.
- [x] `feat(app): add focus actions`
  - `screen_focus_lock` 0=auto 1=top 2=bottom; ported from Pathfinder.
- [x] `feat(app): add swap screens action`
- [x] `feat(app): switch to previous app on focused screen`
- [x] `feat(app): add default ayn binds`
  - L1/R1 focus top/bottom, Select swap focus, Start swap windows, B recents toggle, X mouse mode.
- [x] `feat(app): add home and back pad combos scoped per screen`
  - Home+L1/R1 home top/bottom, Back+L1/R1 previous app top/bottom.
- [x] `feat(app): add vibration and toast feedback for actions`
  - Per-type toggles. Bip = true, brt = false; invert switch.
- [x] `feat(daemon): start mouse mode on focus.current`
  - Newer of last touch and last focus action; init from `screen_focus_lock`.
- [x] `feat(app): link bottom screen volume to media volume`
- [x] `feat(app): add json config export and import`
  - Per-section checkboxes.
- [x] `build: sign release with local keystore`
  - `~/key/thorutil-release.properties`, debug key fallback.
- [x] `chore: bump version to 0.4.0`

## Later

- [ ] `feat(app): add custom cursor from png, cur, svg or jxl`
  - Root overlay, needs restart per change.

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
