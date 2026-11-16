# TODO: unfinished and unverified

Nothing in this list is silently stubbed. Where a path is not implemented, the app refuses
with a message in the UI or the log, as noted.

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
- [ ] Bluetooth serial PTT against a real radio or adapter: connect, key/unkey presets, and
      behavior when the link drops mid-transmission.
- [ ] Store and forward with three real stations, including a relay: re-send when a station
      comes into range, no duplicate messages in ATAK, and how much air time it uses on a busy
      channel.
- [ ] How ATAK orders a late chat message in a conversation: by the CoT/remarks time (it should
      slot in where it was written) or by arrival (it shows at the bottom, with the right time).
      Also confirm ATAK accepts an event whose `time` is well in the past when `stale` is not.
- [ ] Emergency alerts with a real ATAK: that ATAK's 911 / Ring The Bell / In Contact events and
      their cancel look like the samples the tests use (`b-a-o-*`, `<emergency>`, `-9-1-1` uid),
      that rebuilt alerts raise the alarm on the receiving ATAK and the cancel clears it, and
      whether ATAK re-reports an active alert by itself. If it does not, the beacon stops after
      30 minutes without a client report (`EmergencyConfig.forgetMs`); revisit that limit.
- [ ] FX.25 against Dire Wolf in both directions (Dire Wolf `FX25TX` on, and decoding TAKwave's
      FX.25). The code follows Dire Wolf's source and the tests show it repairs damaged frames,
      but no frame has crossed between the two yet.
- [ ] Team profile links: which camera and QR apps open a `takwave://` link directly (some only
      show the text; Paste covers that), and the confirmation dialog on Android 10 and 14.
- [ ] Channel busy figure against a real busy channel (it is DCD sampled at 10 Hz).
- [ ] Setup guide on a fresh install on Android 10, 12 and 13+ (permission prompts differ).
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
- [ ] R8 release build behavior at runtime (it builds; it has not been run).
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
- [ ] **Delivery confirmation** for chat. Store and forward re-sends a message when a station
      that missed it is heard, but nothing confirms that it arrived.
- [ ] **Multi-hop relaying and AX.25 digipeating.** A relay passes a message on once; relayed
      packets are never relayed again, and digipeater paths are never used.
- [ ] **Alert acknowledgment.** Nothing on the air confirms that an alert reached anyone; the
      author keeps repeating it until it is canceled.
- [ ] **Scanning profile QR codes inside TAKwave.** Left to the camera app or a QR reader.
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
