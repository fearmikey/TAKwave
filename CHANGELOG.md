# Changelog

All notable changes to TAKwave are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/). Each release also gets a short entry in
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

## [Unreleased]

## [0.3.1] - 2026-11-16

Over-air protocol version 0, except packets sent with *Encrypt my callsign too*, which use
version 1 (`docs/PROTOCOL.md`).

### Added

- **Encrypt my callsign too** (Settings > Security, off by default, only while encryption is
  on): every frame goes out with the shared placeholder `TAKWV-15` as AX.25 source and the real
  callsign sealed inside the encrypted payload. Uses protocol version 1 for these packets only;
  older TAKwave versions drop them. The setting travels in team profiles. Stations must identify
  themselves by other means while it is on.
- **Radio guide** (Settings > Radio & audio): choose amateur (2 m / 70 cm) or Part 90 business
  band and the terrain, and TAKwave suggests the band, the channel width (business band is
  always 12.5 kHz), the deviation to calibrate for, frequencies to use and to stay off (ARRL
  packet channels; the nationwide Part 90 itinerant channels), radio settings for data, and an
  FX.25 level it can apply. US rules only. The calibration screen shows the deviation target
  for the chosen width.
- **Auto-PTT cable hint:** when Serial PTT is selected but only a USB sound card is attached
  (for example the BTECH APRS-K1 PRO, which keys the radio from the audio and has no serial
  port), Settings and the setup guide explain why and offer "Use VOX instead" (TXDELAY at least
  500 ms), and the start-up warning says the same. Help texts mention such cables and that the
  radio's own VOX stays off with them.

### Changed

- Encrypting stations keep 8 bytes of every chat free for a hidden callsign, so the longest
  encrypted chat text is 8 bytes shorter.
- **Local TAK server moves from port 8087 to 8099.** ATAK has its own TCP input on 8087, so
  ATAK could connect to itself and drop the link every ~30 seconds (even with the gateway
  stopped), or TAKwave could fail to bind. Existing installs on 8087 move to 8099
  automatically, and 8087 can no longer be chosen. **Delete the old TAKwave server in ATAK
  and re-import the data package.**
- **Simpler navigation:** the tab row is gone. Status is the home screen, and the packet log,
  Settings and About open from icons in the top bar; Back returns.
- **Settings in groups:** Station & TAK, Radio & audio, Channel and Security, each on its own
  page, with a summary of the current values on the Settings list.
- **Calibration** opens from *Adjust levels* on the Status screen or from Settings > Radio &
  audio.

### Fixed

- **ATAK got "connection refused"** with *Allow LAN clients* off: the server listened on the
  IPv6 loopback `[::1]` (what Android returns for the loopback address) while ATAK connects to
  `127.0.0.1`. It now binds `127.0.0.1`.

## [0.3.0] - 2026-11-15

Over-air protocol still version 0, with additive changes only: an extension carrying hop count
and age on repeated packets and a report counter on position reports (`docs/PROTOCOL.md` 4.4),
a new Emergency message type (4.5), and optional FX.25 framing (section 1). Receivers that
predate them ignore the extension and drop emergency packets.

### Added

- **About tab:** app version, developer, links to the GitHub repository, the issue tracker
  and Buy Me a Coffee. Links open in the browser only when tapped.
- **Emergency alerts:** ATAK's 911, Ring The Bell, In Contact, geo-fence and custom alerts, and
  their cancellation, go on the air ahead of all other traffic. An active alert is repeated every
  30 s with the latest position until it is canceled in ATAK; the cancel is sent three times.
  Relay stations pass alerts on. Alerts heard from others show as red cards on the Status tab
  and in the notification, with distance and bearing. An old copy of an alert can never undo its
  cancellation.
- **FX.25 forward error correction:** FX.25 packets are always received and repaired (compatible
  with Dire Wolf). Sending with 16, 32 or 64 check bytes is a new setting, off by default. In
  simulation, at a noise level where plain AX.25 decodes 17.5% of packets, FX.25 with 32 check
  bytes decodes 99.5%.
- **Channel health** on the Status tab: channel busy, own air time, packets delivered, repeated,
  repaired by FX.25, other traffic and unreadable, failed sends and queue drops over the last
  10 minutes, with hints when the channel is congested. The Heard list shows the share of each
  station's position reports that arrived (from a new report counter in position reports).
- **Team profiles:** share the settings a team must agree on (position report pacing, channel
  access, time slots with per-callsign slot numbers, store-and-forward limits, FX.25, encryption
  mode) as a QR code, a file or text, and load one from a `takwave://` link, a file or the
  clipboard. Keys and passphrases are never included. TAKwave lists every change and asks before
  applying a profile.
- **Store and forward survives a restart:** held messages, the list of messages already seen and
  the repeat budget are saved and restored, so a restart neither loses waiting messages nor shows
  repeats twice. Held messages are dropped if encryption was switched on or off in between.

- **Store and forward** for chat and markers, so teammates who were out of range still get
  them:
  - *Send my messages again* (on by default): a station re-sends its own chat and markers when
    it hears a station that was not in range when they were sent. Direct messages are re-sent
    only for their recipient.
  - *Relay for other stations* (off by default): a well-placed station passes on chat and
    markers it hears from others, under its own callsign. Relayed packets are marked and never
    relayed again.
  - Limits on hold time, repeats per message, gap between repeats and repeats per 10 minutes,
    all adjustable. New traffic always goes first. Position reports are never repeated.
  - Re-sent and relayed messages arrive in ATAK with the time they were written, not the time
    the copy arrived. A relay that missed a marker edit cannot bring back the old version.
  - Repeats already received are not shown in ATAK a second time. The Log tab marks re-sent,
    relayed and received-via-relay packets, with how long ago they were written.

## [0.2.0] - 2026-11-14

Over-air protocol unchanged (version 0).

### Added

- **Bluetooth serial PTT:** key the radio by sending a command over a Bluetooth serial (SPP)
  link to a paired radio or adapter. Presets for Kenwood/Elecraft and Yaesu CAT, or custom
  key and unkey commands (with `\r`, `\n` and `\xHH` escapes). Transmitting is disabled if the
  link cannot be opened or drops. Audio still uses a cable or USB sound card. Radios that only
  pair with a Bluetooth earpiece (headset profile) are not supported.
- **Setup guide** on first start: walks through the permissions, then offers a quick start
  that asks for the callsign, how the radio is connected, and only the settings that setup
  needs. It can be run again from the Settings tab.
- **Info buttons** next to each settings category that explain it in plain language.

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

[Unreleased]: https://github.com/fearmikey/TAKwave/compare/v0.3.1...HEAD
[0.3.1]: https://github.com/fearmikey/TAKwave/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/fearmikey/TAKwave/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/fearmikey/TAKwave/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/fearmikey/TAKwave/releases/tag/v0.1.0
