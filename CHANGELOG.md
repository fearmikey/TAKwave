# Changelog

All notable changes to TAKwave are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/). Each release also gets a short entry in
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

## [Unreleased]

## [0.4.1] - 2026-11-26

Over-air payload unchanged.

### Changed

- **A radio or TNC that keys itself no longer looks like it is transmitting.** With a P25, DMR,
  D-STAR or NXDN radio or a KISS TNC, TAKwave only knows that a packet was handed over, not
  whether it went out on the air. The Status screen now says so: a "LINK UP" / "NO LINK" badge
  replaces the IDLE badge (the notification says the same), the input level meter is hidden,
  "Sent" reads "Handed to the radio", the air time is marked as an estimate, and the log
  explains at start that TX means handed to the radio.
- **P25 help text names the APX settings.** On an APX portable the packet data settings are in
  the data profile (Data Configuration > Data Profiles): Packet Data Mode must be Repeated or
  Direct, not FNE (which waits for a data gateway), Limited Broadcast must be checked to send
  to 255.255.255.255, and Terminal Data must be checked. Found in the first test with an
  APX 8000, which took the packets on USB and never keyed until its data profile was changed.

### Added

- **Delivery errors from a P25 or DMR radio are shown.** When the radio (or a host beyond it)
  answers one of TAKwave's packets with an ICMP error such as "destination unreachable",
  TAKwave now raises a warning that names the destination and the reason, logs every repeat
  and counts the packet as failed. Before, these answers were dropped without a trace. Other
  ICMP messages appear in the raw radio data log.

## [0.4.0] - 2026-11-18

Over-air payload unchanged.

### Added

- **P25 radio packet data** (Settings > Radio & audio > Modem backend, and a new choice in the
  setup guide): use a digital P25 radio with the APCO packet data option, such as a Motorola
  XTS 2500 flashed with Q947/W947, as the modem. Radios from other makers (L3Harris, Kenwood,
  BK/EF Johnson) that offer IP packet data on USB or a serial cable can be chosen too. TAKwave
  connects to the radio's serial data cable through a USB-to-RS-232 adapter, speaks PPP or SLIP
  to it, and sends each frame as a UDP datagram that the radio carries over its own P25 modem,
  to every radio on the channel (broadcast) or to listed radio addresses. Over-air payload
  unchanged; see `docs/PROTOCOL.md` section 10. Not yet tested against a real radio.
- **P25 radio auto-setup.** Plugging a Motorola radio in on its USB cable offers to use it as
  the modem. With auto-detect on (the default), TAKwave finds the radio's data port baud rate
  and framing (PPP or SLIP) every time it connects, and keeps the radio's address and the
  addresses PPP negotiates. Nothing is sent over the air while detecting. A "Detect radio
  settings now" button runs the same check from Settings and the setup guide.
- **APX portables over USB** (APX 6000, APX 8000): the radio's USB cable presents it as a small
  network adapter (RNDIS; CDC-ECM also handled), and TAKwave now drives that link itself, with
  no root. It gets the addresses from the radio's DHCP server when it has one, finds the radio
  with ARP, and carries packets exactly as on the serial cable. Plugging the radio in offers to
  use it. Not yet tested against a real radio.
- **MOTOTRBO (DMR) radio data** (Settings > Radio & audio > Modem backend, and a new choice in
  the setup guide): use a Motorola MOTOTRBO radio, such as an XPR 6550, as the modem on its USB
  programming cable. TAKwave drives the radio's USB network link itself (as for APX portables),
  gets the addresses from the radio, and sends each frame as a UDP datagram to the team's
  talkgroup (a group data call; talkgroup 1 is 225.0.0.1) and optionally to listed radio IDs.
  Sending is paced for DMR's slower data rate. Plugging in a Motorola radio on USB now asks
  whether it is MOTOTRBO or APX when its name does not say. The radio needs "Forward to PC" on;
  the help text lists the CPS settings. See `docs/PROTOCOL.md` section 10. Not yet tested
  against a real radio. Amateur DMR radios (AnyTone, Ailunce, TYT) cannot pass data to a phone
  and are not supported.
- **Hytera DMR radios** in the same backend (now "DMR radio data: MOTOTRBO or Hytera"): choose
  the make, and the network numbers follow it. Hytera defaults are a best guess; not yet tested.
- **D-STAR and NXDN radio data** (new modem backend and setup guide choices): use an Icom
  D-STAR radio (IC-705, ID-52, IC-9700 and similar) in DV data mode, slow data or DV Fast Data,
  or a Kenwood NX / Icom IDAS NXDN radio with transparent data, as the modem. TAKwave reaches it
  over USB serial (the radio's own port, any port of a dual-port bridge, or an RS-232 adapter)
  or Bluetooth serial (the radio's own Bluetooth or an adapter). Each frame goes as KISS with
  its FCS so damaged frames are dropped; sending is paced per radio type. See
  `docs/PROTOCOL.md` section 10.2. Not yet tested against a real radio.
- **Fallbacks for the untested digital radios** (Settings > Radio & audio):
  - D-STAR / NXDN: a **Text encoding** (Base64 lines) for radios that alter bytes or send data
    line by line. Every station reads both encodings. After repeated damaged packets the app
    suggests it.
  - DMR: **how talkgroup packets are handed to the radio** (radio as router, IP multicast, or
    broadcast), and **custom destination addresses** that replace the computed ones.
  - All digital radios: **pacing by hand** (data rate and key-up time), and **Log raw radio
    data**, which records what the radio sends that TAKwave does not use (other ports, damaged
    packets, raw bytes) for troubleshooting.
- **Bluetooth LE TNCs.** KISS over Bluetooth can now use the Bluetooth LE KISS service, as offered
  by the Mobilinkd TNC4 and other BLE TNCs, besides classic Bluetooth serial. A new "Bluetooth
  link" setting picks classic, Bluetooth LE or Automatic. "Find a Bluetooth LE TNC nearby" uses
  Android's device chooser, so no location or scan permission is needed. Not yet tested against a
  real TNC.
- **P25 over Bluetooth.** The P25 backend can reach a radio through a Bluetooth serial adapter on
  its RS-232 data cable instead of USB. Auto-detect finds PPP or SLIP through it; the adapter's
  own setting fixes the baud rate.
- **Radio hints.** Choosing a paired BTECH UV-PRO, Radioddity GA-5WB, Vero VR-N76, Kenwood
  TH-D74/TH-D75, Mobilinkd TNC or Icom IC-705/ID-52 explains how TAKwave uses it best and offers
  a button that sets it up.
- **Bluetooth PTT presets** for the Kenwood TH-D74/TH-D75 and the Icom IC-705 (CI-V).

### Changed

- Classic Bluetooth connections now also try the serial port without encryption, and for data
  links (KISS, radio data) any serial service of the radio's own, before giving up. PTT commands
  still only go to the standard serial port.

## [0.3.2] - 2026-11-16

### Added

- **KISS over Bluetooth** (Settings > Radio & audio > Modem backend, and a new choice in the
  setup guide): use a radio with a built-in Bluetooth KISS TNC, such as the BTECH UV-PRO, with
  no audio cable. TAKwave exchanges packets with the paired radio over a Bluetooth serial link
  and the radio does the modulation, channel access and keying. The link reconnects on its
  own. Not yet tested against a real radio.

### Changed

- With a KISS TNC (TCP or Bluetooth), the Status screen shows "(not connected)" while the link
  is down, and connects and disconnects are written to the log.

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

[Unreleased]: https://github.com/fearmikey/TAKwave/compare/v0.4.1...HEAD
[0.4.1]: https://github.com/fearmikey/TAKwave/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/fearmikey/TAKwave/compare/v0.3.2...v0.4.0
[0.3.2]: https://github.com/fearmikey/TAKwave/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/fearmikey/TAKwave/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/fearmikey/TAKwave/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/fearmikey/TAKwave/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/fearmikey/TAKwave/releases/tag/v0.1.0
