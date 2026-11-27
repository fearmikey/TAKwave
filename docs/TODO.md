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
- [ ] KISS over Bluetooth against a real radio with a built-in TNC (BTECH UV-PRO): that it
      offers KISS on the standard SPP service, connects while paired, sends and receives
      TAKwave frames, reconnects after the radio is switched off and on, and whether its TNC
      passes the longest frames (a full chat message, about 270 bytes) or truncates them.
- [ ] KISS over Bluetooth LE against a real BLE TNC (Mobilinkd TNC4): that the companion device
      chooser lists it, that the BLE KISS service and characteristics are as in `core/io/BleKiss`,
      MTU negotiation, acknowledged writes of a 270-byte frame, notifications arriving in order,
      and reconnecting after the TNC is switched off and on. Also Automatic link choice on a
      dual-mode TNC3.
- [ ] The Bluetooth names radios really advertise (UV-PRO, GA-5WB, VR-N76, TH-D74/D75, IC-705,
      ID-52, TNC4), so `core/radio/BluetoothRadios` recognises them; and whether the TH-D74/D75
      key with `TX`/`RX` + CR and the IC-705 with CI-V `1C 00` over Bluetooth.
- [ ] SPP fallbacks: that an adapter refusing the encrypted socket connects without encryption,
      and whether any supported radio offers its data port only under a vendor service.
- [ ] P25 packet data through a Bluetooth serial (SPP) adapter on the radio's RS-232 data cable:
      that the adapter is 8-bit clean at the radio's rate, that framing detection works through it,
      and that the reconnect after detection succeeds.
- [ ] P25 packet data with two real P25 radios (Motorola XTS 2500 or similar with Q947/W947),
      on a conventional simplex channel: which framing (PPP or SLIP) and baud rate the radio's
      data port actually uses; whether its PPP accepts TAKwave's minimal client (no
      authentication; LCP and IPCP options); whether 255.255.255.255 with Limited Broadcast
      reaches the other radio and is passed to its mobile computer, or whether only unicast to
      air-interface addresses works; whether the radio needs DTR asserted on the data port
      (TAKwave holds it low); real throughput, to tune the 4 kbit/s / 300 ms pacing estimate;
      and how lossy it is (one report says about half of raw IP packets got through, while
      the radio's own text messages were reliable). Also whether the XTS 2500's USB cable
      appears as a USB serial device on Android at all, and what vendor/product id it reports.
- [ ] APX 6000 / APX 8000 on USB. First field test (APX 8000, firmware R36.00.02, conventional
      P25, 2026-11-26): the radio is found on Android, `Rndis.initialize` succeeds and the IP
      link comes up, so packets reach the radio. It did not key: its data profile had Packet
      Data Mode FNE and Limited Broadcast unchecked (Terminal Data, NAT Enable, Auto Generate IP
      Address and Auto Generate Target IP Address were checked, ARS disabled). APX CPS offers
      Packet Data Mode FNE, Repeated and Direct. With Repeated and Limited Broadcast checked,
      the tester confirmed the radio transmits TAKwave's packets sent to 255.255.255.255 (the
      default destination). Still to check: whether Direct works as well (the tester expects it
      to be the better choice for radio to radio); whether the radio answers with ICMP errors
      when it cannot send (now shown as a warning); what addresses its DHCP server hands out;
      and whether a second radio passes UDP to port 50125 from the air to its phone. With NAT Enable on that may need an entry in Data Wide > NAT List (LAN
      port, static NAT IP address, WAN port) for the phone's address, or NAT off: a guess from
      the field names, untested. Also whether the phone powers the radio's USB side or needs a
      powered OTG hub, and whether CPS-style radio management on the same link (port 8675)
      interferes.
- [ ] MOTOTRBO (XPR 6550 or later) on USB. Seen on Linux: the XPR 6550 enumerates as 0cad:1020,
      product name "Motorola Solutions LTD Device", full speed, interface 0 CDC 02/02/FF (RNDIS,
      interrupt EP 0x81) and interface 1 CDC data (bulk 0x82 in, 0x03 out, 64 bytes); the kernel's
      `rndis_host` binds to it. `DetectedRadio.systemOf` now treats product id 0x1020 as
      MOTOTRBO. Still to check: the same on Android; that `Rndis.initialize` succeeds; which
      addresses the radio's DHCP server hands out (the Linux PC's interface ended up in
      192.168.99.0/24, not the 192.168.10.x default) and that it names itself as router; that a
      datagram to 225.0.0.1 sent to the radio's MAC goes out as a group data call and reaches a second radio's phone with "Forward to PC" on, and in what form
      (destination 225.x.y.z or rewritten to the phone's address; source the sender's 12.x.y.z);
      which of the three talkgroup delivery settings the radio accepts; how the compressed
      UDP header setting affects it; real throughput and delay, to tune the 1.2 kbit/s / 500 ms
      pacing; and how lossy unconfirmed group data is. Also whether CPS on the same link or ARS
      interfere. (The XPR 6550 first failed to enumerate at all on two Linux PCs; it later
      came up, so check the cable seating if a radio stays silent on USB.)
- [ ] Hytera DMR on USB: that the cable enumerates as RNDIS or CDC-ECM on Android, its USB ids,
      DHCP behaviour, the real data forwarding setting names, and whether the radio and
      talkgroup networks really default to 10 and 225 and route group data like MOTOTRBO.
- [ ] P25 radios from other makers (L3Harris XG/XL, Kenwood NX-5000, BK/EF Johnson): whether
      they offer IP packet data on USB (network adapter) or a serial cable (PPP/SLIP), their
      default cable addresses, and whether radio-to-radio data works without infrastructure.
- [ ] Icom D-STAR DV data (IC-705, ID-52, IC-9700): that a TAKwave frame written to the data
      port with DV Data TX on Auto comes out of the other radio's data port byte for byte (8-bit
      clean; if not, the Text encoding should get through: check which one works), the
      menu names, which port of the IC-705's USB bridge carries DV data, the Bluetooth data
      profile on the ID-52 and IC-705, the largest frame the radio buffers before it starts
      transmitting, and real throughput for slow data and DV Fast Data.
- [ ] Kenwood NX / Icom IDAS NXDN transparent data: which models and options offer it, the
      programming settings, whether it is byte transparent, and real throughput.
- [ ] P25 auto-detect against a real radio: that a radio answers the LCP request or the TTL-1
      ping within 1.5 s, that the ping is really not forwarded over the air (watch a second
      radio or a scanner while detecting), and that a SLIP radio's ping reply names its address.
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
- [ ] **USB CDC-NCM radios.** RNDIS and CDC-ECM are implemented; NCM is not.
- [ ] **DHCP lease renewal** on the USB network link. Addresses are fetched on every connect
      and never renewed; a radio that expires leases quickly would need it.
- [ ] **P25 on trunked systems.** Untested; needs the system's packet data infrastructure and
      data registration, which TAKwave neither does nor checks.
- [ ] **Bridging AFSK, P25, DMR, D-STAR and NXDN stations.** A station uses one backend at a time.
- [ ] **USB detection for Hytera, Icom and Kenwood radios.** Only Motorola radios open the setup
      dialog when plugged in (DECISIONS 143).
- [ ] **D-STAR gateway (reflector) routing.** Only local RF (simplex or a repeater without
      linking) is covered; data through D-STAR gateways is untested.
- [ ] **MOTOTRBO text messages (TMS, port 4007) and location reports (LRRP, port 4001).** A
      bridge would show radio text messages (including from amateur DMR radios that send
      Motorola-format SMS) as GeoChat and radio GPS reports as map positions. Not started; needs
      captures from a real radio (DECISIONS 137).
- [ ] **MOTOTRBO on Capacity Plus / Linked Capacity Plus.** Needs data revert channels; untested.
- [ ] **Amateur DMR radios** (AnyTone, Ailunce, TYT). They cannot pass data to a phone; not
      supported (DECISIONS 136).
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
