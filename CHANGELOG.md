# Changelog

All notable changes to TAKwave are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/). Each release also gets a short entry in
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

## [Unreleased]

## [0.1.0] - 2026-11-14

First release. Over-air protocol version 0.

### Added

- **Local TAK server** on `127.0.0.1:8087` (plain TCP, streaming CoT XML) for ATAK-CIV and
  other TAK clients, with optional LAN access, relay between connected clients, local handling
  of pings, and a hardened XML parser (no DTDs or entities, 64 KB event cap).
- **ATAK data package export** that configures the server connection in ATAK.
- **Over-air traffic:** position reports (PLI), GeoChat (group and direct), and point markers,
  in a compact binary format inside AX.25 UI frames addressed to `TAKWV`. A typical position
  report is 37 bytes.
- **Software modem:** Bell 202 AFSK at 1200 baud with a phase-continuous modulator and a
  multi-slicer demodulator with PLL clock recovery, carrier detect, and an input level meter.
- **Throttling:** latest-only PLI per station on a 60 s base interval with early reports on
  movement or turns, a priority transmit queue (chat, markers, PLI), and a 2-minute stale limit.
- **Channel access:** p-persistent CSMA, plus optional **time-slotted transmit** aligned to UTC,
  using the phone clock or GNSS time, so stations take turns.
- **Loop prevention:** traffic received from RF is never retransmitted.
- **Radio interfaces:** USB audio device selection, serial RTS/DTR PTT through a USB serial
  adapter (for example a Digirig), VOX, external KISS TNC over TCP, and an acoustic test mode
  using the phone's own speaker and microphone.
- **Safety:** no transmission without a valid callsign; PTT released after every transmission,
  on service stop, on USB detach, and by a transmit timeout watchdog.
- **Optional payload encryption** for Part 90 use (off by default, not permitted on amateur
  bands): AES-GCM or ChaCha20-Poly1305 with a passphrase, a symmetric key file, or a key
  derived by ECDH between two stations; and ECIES to a team public key. EC P-256 and X25519
  keys are loaded from PEM files.
- **App:** foreground service with a status notification, and Status, Settings, Log and
  Calibrate screens (calibration tones, level guidance, test PLI).
- **Packaging:** F-Droid recipe, Fastlane metadata, reproducible-build settings, and a build
  check that rejects non-free Google dependencies.

### Known limitations

- Not yet verified on physical phones and radios. See `docs/TODO.md`.

[Unreleased]: https://github.com/fearmikey/TAKwave/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/fearmikey/TAKwave/releases/tag/v0.1.0
