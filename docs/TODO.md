# TODO: unfinished and unverified

Nothing in this list is silently stubbed. Where a path is not implemented, the app refuses
with a message in the UI or the Log tab, as noted.

## Not verified on real hardware

The development environment had no phone, radio or TAK client attached. Everything below is
implemented and compiles, and the logic underneath is unit-tested in `:core`, but none of it
has been run end to end.

- [ ] Acoustic test between two phones each running ATAK (the release acceptance test).
- [ ] ATAK-CIV importing the exported data package and connecting. The package format follows
      the documented `cot_streams` convention but has not been imported into a real ATAK.
- [ ] GeoChat and marker rendering in ATAK for rebuilt events (golden tests check structure
      against ATAK-style samples written from the documented schema, not live captures).
- [ ] Digirig serial PTT (RTS) and USB audio device routing on a real phone.
- [ ] Audio latency: `AndroidAudioSink` waits a fixed 80 ms after the playback head catches up
      before unkeying. Devices with more output latency may clip the end of a packet; raise
      TXTAIL if so.
- [ ] Foreground service start on Android 14 and 15 with each combination of permissions.
- [ ] GNSS time source. `SystemClock.currentGnssTimeClock()` is expected to work while another
      app (ATAK) keeps the GPS on; if it does not, the app says "GNSS time unavailable, using
      phone clock" and carries on with the phone clock.
- [ ] X25519 keys and ChaCha20-Poly1305 on older Android versions. Where the platform lacks
      them, key import or gateway start fails with "this Android version does not support the
      selected algorithm". EC P-256 with AES-GCM works everywhere.
- [ ] R8 release build behaviour at runtime (it builds; it has not been run).
- [ ] Reproducible build: settings are in place but two independent builds have not been compared.

## Not implemented

- [ ] **Password-protected private keys** (`BEGIN ENCRYPTED PRIVATE KEY`). Import is refused
      with instructions to decrypt first.
- [ ] **RSA keys.** Refused on import: an RSA ciphertext does not fit in a 256-byte frame.
- [ ] **Legacy key formats** (SEC 1 `EC PRIVATE KEY`, OpenSSH, PGP, certificates). Refused on
      import with a conversion hint.
- [ ] **Hardware-backed key storage.** Keys and the passphrase are stored in app-private
      files, not the Android Keystore.
- [ ] **Replay protection** for encrypted traffic, and forward secrecy for shared-key modes.
- [ ] **Time slots with the KISS backend.** The external TNC does its own channel access; the
      slot settings are ignored there (the settings screen hides them).
- [ ] **Slot coordination over the air.** Slot numbers are configured by hand or derived from
      the callsign; stations do not negotiate or detect slot clashes.
- [ ] **Live settings changes.** Settings are read when the gateway starts; use "Restart
      gateway now".
- [ ] **Delivery confirmation or retries** for chat. Each message is sent once.
- [ ] **Digipeating.** Frames with a digipeater path are received, but TAKwave never repeats.
- [ ] **Shapes, routes, files, custom icon sets.** Only points are carried. Markers that use
      a custom icon set arrive with the default icon for their type.
- [ ] **Direct-message filtering.** A direct GeoChat is delivered to every local TAK client;
      ATAK shows it only to the addressee.
- [ ] **Instrumented and UI tests.** All tests are JVM tests in `:core`.
- [ ] **Light theme and translations.**

## Before the first tagged release

- [ ] Replace the `commit` placeholder in `fdroid/com.fearmikey.takwave.yml` with the tagged commit.
- [ ] Add screenshots under `fastlane/metadata/android/en-US/images/phoneScreenshots/`.
- [ ] Build the signed `app-release.apk` and attach it to the GitHub release.
