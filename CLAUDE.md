# CLAUDE.md — ADAS Edge

Project-specific notes for working in this repo.

## Device testing (adb)

`adb` is at `~/android-build/sdk/platform-tools/adb` (on `PATH` via `~/.bashrc`).
Test device: Galaxy S26 Ultra (`canoe`, Hexagon HTP **v81**).

### Keep the screen awake while plugged in
The phone aggressively dozes/locks (black or portrait screen) during long test
sessions. Keep it awake while on USB power:

```bash
# ENABLE — stay awake while plugged into USB
adb shell settings put global stay_on_while_plugged_in 2
```

```bash
# DISABLE — restore normal screen sleep
adb shell settings put global stay_on_while_plugged_in 0
```

`stay_on_while_plugged_in` is a bitmask: `0` = off, `1` = AC, `2` = USB,
`4` = wireless; sum to combine (e.g. `3` = AC+USB, `7` = all). Check current value
with `adb shell settings get global stay_on_while_plugged_in`.
