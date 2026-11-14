# Vendored: usb-serial-for-android

| | |
|---|---|
| Upstream | https://github.com/mik3y/usb-serial-for-android |
| Version | v3.9.0 (commit `e1018ab31c118b7d2e15fc7c2e9c5b19f33eb1f1`) |
| License | MIT, see `LICENSE.txt` in this directory |
| Copyright | 2011-2013 Google Inc., 2013 Mike Wakerly, and contributors |

`src/main/java` is an unmodified copy of upstream `usbSerialForAndroid/src/main/java`.
Only `build.gradle.kts` and this file were written for TAKwave.

The library is published only on JitPack, which F-Droid cannot use for reproducible builds, so
the source is built as a local module instead (see `docs/DECISIONS.md`).

## Updating

1. Check out the new upstream tag.
2. Replace `src/main/java` with upstream `usbSerialForAndroid/src/main/java`.
3. Update the version and commit above, and `LICENSE.txt` if it changed.
4. The keep rule for the driver classes (they are found by reflection) lives in
   `app/src/main/keepRules/rules.keep`.
