# Decisions

Judgment calls made while building TAKwave 0.1.0, with the reasoning. Newest sections last.

## Build and dependencies

1. **`compileSdk 37`, `targetSdk 35`, `minSdk 29`.** The spec fixes min and target. Compile SDK
   stays at the level the project template and current AndroidX releases expect.
2. **AGP built-in Kotlin, `optimization { enable = true }`.** AGP 9.5 ships its own Kotlin
   support and a new R8 DSL; keep rules live in `app/src/main/keepRules/rules.keep`.
3. **usb-serial-for-android is vendored** in `:usbserial` (v3.9.0, unmodified source, MIT) rather
   than pulled from JitPack, for F-Droid reproducibility. `buildConfig` is enabled in that
   module only because upstream code references `BuildConfig.DEBUG`.
4. **kXML 2 is the XML parser, repackaged by `:xmlparser`.** The kXML jar bundles the
   `org.xmlpull.v1` API, which Android also provides; R8 refuses that combination. `:xmlparser`
   builds a jar of kXML without `org/xmlpull/**`. `:core` compiles and tests against the full
   jar. The alternative, Android's built-in `Xml.newPullParser()`, would have meant the parser
   that ships is not the parser the hardening tests run against.
5. **No Material Components or AppCompat.** The UI is Compose Material 3 only; the window theme
   is the platform `Theme.Material.NoActionBar`.
6. **`dependenciesInfo` is disabled** in the APK and bundle: that block is encrypted with a
   Google key and F-Droid rejects it.
7. **FOSS guard also blocks Play Billing, Play Core and install referrer**, beyond the three
   groups the spec names. It inspects every resolvable `*RuntimeClasspath` and runs before
   every `:app` build.
8. **`BuildConfig` is off in `:app`**, so no build-time values can leak into the APK.
9. **Tests use JUnit 4** and live only in `:core`.
10. **The store icon is rendered by a Gradle task** (`generateFastlaneIcon`) from the same
    geometry as the launcher vectors, and the PNG is committed.

## Protocol

11. **Version byte is `0x54 + version`,** so protocol version 0 is ASCII `T`.
12. **Encrypted packets hide the message type.** Byte 1 carries only the ENC flag and the
    cipher suite; the real type byte is the first byte of the plaintext. This matches "encrypt
    only the payload after the version byte, set a header flag".
13. **Identifiers are packed.** `ANDROID-<16 hex>` becomes 8 bytes and UUIDs 16 bytes, which
    is what gets a PLI down to 37 bytes. Anything else is sent verbatim, capped at 48 bytes.
14. **Common CoT types are a one-byte code** from an append-only table; others are literal.
15. **PLI carries the sender's base interval** (1 byte, units of 5 s) so receivers can set the
    stale time to three intervals, as the spec asks.
16. **Markers carry an optional ARGB color** in addition to the spec's fields, because spot-map
    markers are identified by color in ATAK.
17. **Payload is capped at 256 bytes** (the AX.25 default) so hardware TNCs can carry it. Caps:
    uid 48, callsign 20, room 32, chat text 200 (reduced further when needed to fit).
18. **Unknown team or role codes decode as "unknown"**; unknown type codes drop the packet.
19. **Coordinates out of range are rejected** on receive rather than passed to the map.

## CoT handling

20. **What counts as "my own position".** An `a-*` event with a `<takv>` or `<__group>` detail
    is a self-report and becomes PLI. An `a-*` event with `how="h-g…"`, a `<usericon>` or
    `<archive>` is a user-dropped marker. Other atoms (tracks the client is merely relaying)
    are not sent.
21. **Rebuilt PLI defaults:** team Cyan and role Team Member when unknown; `how="m-g"`; contact
    endpoint `*:-1:stcp`.
22. **Rebuilt markers get `<archive/>`** and the spec's five-minute stale time. Without
    `<archive/>` ATAK would delete a marker five minutes after it arrived.
23. **Rebuilt chat uses the sender's last known position** (from their PLI heard on RF), or
    0,0 with unknown error when none has been heard.
24. **Pings (`t-x-c-t`) get a `t-x-c-t-r` reply** and are neither relayed nor transmitted.
25. **Every valid client event is relayed to the other local clients,** including types that
    are not eligible for RF. Malformed events are dropped.
26. **DOCTYPE and ENTITY declarations cause the whole event to be rejected** before parsing,
    in addition to the parser never processing DTDs. Nesting deeper than 16 is rejected.
27. **The stream splitter drops an oversized event up to its closing tag** and keeps the
    connection, so one bad event does not cost the client its session.

## Scheduling and loop prevention

28. **A PLI is only sent if the client reported since the last transmission.** When a TAK
    client disconnects, its position stops being broadcast.
29. **Heading changes count only above 0.5 m/s,** to ignore compass wander while stationary.
30. **Queue limit 32.** When full, the least important oldest entry is evicted; a PLI is
    refused when the queue is full of chat.
31. **Loop guard:** PLI uids and chat message ids heard on RF are blocked for 10 minutes
    (refreshed on every reception). A marker heard on RF is blocked only while unchanged, so a
    user can edit a received marker and share the edit.
32. **Identical payloads within 5 s are treated as duplicates.** Longer would suppress the
    legitimate repeat of a stationary station's PLI.
33. **Frames from our own callsign are ignored** (hearing ourselves through a repeater or in
    acoustic mode).

## Modem and channel access

34. **Demodulator design:** band-pass prefilter, quadrature correlators for mark and space,
    five slicers with different space-tone gains (0.6 to 4.0) each with its own PLL and HDLC
    decoder, CRC de-duplication. No decimation; 48 kHz is cheap enough on a phone.
35. **DCD is PLL lock quality, accelerated by flag detection.** Flags alone trigger falsely on
    open-squelch noise about five times a second, so DCD asserts when at least 27 of the last
    32 tone transitions landed where the PLL expected, or on a flag while the PLL is half
    locked. It clears after 16 bit times with no transitions.
36. **Energy-based busy detection is off by default** (threshold 0). With an open squelch or in
    acoustic mode, noise would block transmission forever.
37. **A frame that cannot get on the channel within 30 s is dropped** and logged.
38. **A transmission longer than the TX timeout is refused before keying.** The watchdog also
    force-unkeys and aborts the audio if a transmission overruns for any reason.
39. **`:core` uses plain threads and blocking calls** for the audio and transmit paths, which
    are simplest to make correct for real-time audio. Coroutines and Flow are used in the app
    layer for settings, UI state and the service.
40. **Time slots are aligned to the Unix epoch** so stations need no over-the-air coordination,
    only correct time. Guard time is 250 ms. Carrier sensing remains active within a slot, and
    there is no random back-off inside a station's own slot.
41. **The automatic slot is CRC-16 of the callsign modulo the slot count.** It can collide, and
    the UI says so.
42. **GNSS time comes from `SystemClock.currentGnssTimeClock()`,** which needs no location
    permission and works while another app (ATAK) is using the GPS. Adding a location
    permission and a third foreground-service type just for time was judged not worth it. The
    correction is applied only to slot timing; CoT timestamps use the phone clock.
43. **Time slots apply to the soft modem only.** A KISS TNC performs its own channel access.

## Encryption

44. **Encryption never silently falls back to clear text.** If the mode is on but the key
    material is missing or unusable, transmitting is disabled and the Status screen says why.
45. **Cipher suites are identified in the header** (3 bits), so a mismatch is reported as such.
46. **"Asymmetric" is offered in two forms** that fit a broadcast channel with 256-byte frames:
    static ECDH between two stations (derives a shared key), and ECIES to a team public key
    (48 to 49 bytes of overhead; holders of only the public key can send but not read).
47. **RSA is refused** because its ciphertext cannot fit in a frame. Supported curves are
    P-256 and X25519. P-256 points are sent compressed (33 bytes).
48. **ECIES uses a zero GCM nonce** because each derived key encrypts exactly one packet.
49. **Key files live in app-private storage** and the passphrase in DataStore; backups are
    disabled (`allowBackup="false"`). Private keys are never fingerprinted or logged.
50. **PBKDF2 uses a fixed public salt** (required by the spec so stations interoperate), with
    100,000 iterations.

## Android

51. **`CHANGE_NETWORK_STATE` is declared** solely because Android 14+ requires one of a short
    list of permissions (or a granted USB device) to start a `connectedDevice` foreground
    service, and VOX users have no USB serial device. It is a normal, auto-granted permission.
52. **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is declared** to show the direct exemption prompt
    the spec asks for. Not acceptable on Google Play without justification; fine for F-Droid.
53. **The service is `START_NOT_STICKY`.** A microphone foreground service cannot be restarted
    from the background, so a sticky restart would only crash.
54. **The microphone service type is added only when the soft modem is selected and the
    permission is granted;** otherwise the service runs as `connectedDevice` only.
55. **Settings are read when the gateway starts.** Every Settings screen offers a restart button while the gateway runs.
56. **Audio capture prefers the `UNPROCESSED` source,** falling back to `VOICE_RECOGNITION`, to
    keep AGC and noise suppression away from the modem signal.
57. **Audio devices are remembered by type and product name,** since Android device ids change
    on every reconnect. If the chosen device is absent the default is used, with a warning.
58. **When serial PTT is selected but unavailable, transmitting is disabled** instead of
    playing audio into an unkeyed radio.
59. **The test PLI uses uid `TAKWAVE-<callsign>`** and the last local position, or 0,0.
60. **Dark theme only,** and no icon font dependency. Status is the home screen; Log, Settings
    and About open from top bar icons, and every other screen has a back arrow. Settings is a
    short list of groups (Station & TAK, Radio & audio, Channel, Security), each on its own
    page. Calibration is occasional setup work, so it opens from Radio & audio or from *Adjust
    levels* on the Status screen rather than having a place in the main navigation. The icons
    are vector drawables in the app (`ic_log`, `ic_settings`, `ic_about`, `ic_back`,
    `ic_chevron`). Navigation is a small back stack in `MainActivity`, not a library.
61. **At most 8 TAK clients;** a client that stops reading is disconnected once 256 events
    are queued for it.

## Process

62. **`AGENTS.md` was adapted from the owner's Garage project** when the spec referred to a
    file that was not yet in this repository.
63. **Commits are made per phase with no AI attribution; nothing is pushed or tagged.**

## Bluetooth PTT and setup guide

64. **Bluetooth PTT is a serial (SPP) link carrying key and unkey commands,** because that is
    the only Bluetooth keying an Android app can do with public APIs. Android's RFCOMM socket
    does not expose RTS/DTR, so control lines cannot be toggled as with USB serial. Radios
    that only pair with a Bluetooth earpiece or PTT button (the Ailunce HD2's manual describes
    nothing else) expect the other side to be a headset; Android offers apps no way to take
    the hands-free role, so those radios cannot be keyed directly.
65. **Only already-paired devices are listed,** so the app needs `BLUETOOTH_CONNECT` (and the
    legacy `BLUETOOTH` up to Android 11) but neither a scan nor a location permission. A
    device must be chosen explicitly; there is no "first device found" as with USB, since
    commands must never go to an unrelated paired device.
66. **When the Bluetooth link cannot be opened or drops, transmitting is disabled,** as with
    serial PTT. A dropped link cannot carry the unkey command, so the UI tells the operator
    to set the radio's own time-out timer. Only PTT uses Bluetooth; audio does not.
67. **Command syntax is parsed in `:core`** (`PttCommand`) so it is unit-tested; an invalid
    command disables transmitting rather than sending something else.
68. **The setup guide writes straight to the settings store** with the same controls as the
    Settings screens, and is shown until it is finished or skipped (`onboardingDone`). The info
    icon is drawn in code, keeping the no-icon-font rule (60).

## Store and forward

69. **Two modes, different defaults.** Re-sending one's own chat and markers is on by default:
    it never puts anyone else's traffic on the air and only triggers when a new station is
    heard. Relaying other stations' traffic is off by default and meant for one well-placed
    station; it is the one deliberate exception to "never retransmit RF traffic" (31, AGENTS.md).
70. **"Missed it" is inferred from presence, without acknowledgments.** A station counts as
    having had a chance at a message if it was heard within 3 minutes of a transmission of it.
    Hearing someone proves nothing about them hearing us, so this is best effort; acks would
    cost a frame per recipient per message on a 1200 baud channel.
71. **Position reports are never held or relayed.** A stored position is a wrong position, and
    the next live report replaces it anyway.
72. **One hop, marked in the packet.** The relay byte is appended after the body (an additive
    change, no version bump) and a packet that carries it is never relayed again, so loops are
    impossible regardless of how many relays are on the air. The relay transmits under its own
    callsign, which keeps station identification correct.
73. **Every repeat is limited four ways:** hold time (30 min), repeats per message (3), gap
    between transmissions of one message (2 min, counting copies heard from others) and a
    station-wide budget (6 per 10 min, about 12 s of air time at worst). Repeats are sent only
    when the transmit queue is empty, so fresh traffic always wins. The limits are settings so
    a group can tighten them, within ranges that keep the worst case bounded.
74. **Hearing another copy cancels a pending repeat,** and relays wait 4 to 10 s at random
    (authors 2 to 5 s), so the author usually goes first and two relays rarely both transmit.
75. **A seen-cache keeps repeats out of ATAK.** Chat ids and the newest version of each marker
    are remembered for 3 hours. A marker written earlier than the newest version is dropped, so
    a relay that missed an edit cannot undo it.
76. **Repeats carry the message's age, not a timestamp.** The over-air format has no time
    field, and an absolute one would need every station's clock to agree. An age in seconds
    (2 bytes, up to about 18 hours) lets each receiver compute the original time on its own
    clock, consistent with how it stamps everything else. The error is the time the frame
    spends waiting for the channel, at most 30 s. First transmissions stay byte-for-byte
    unchanged; repeats cost 3 bytes (about 20 ms at 1200 baud).
77. **Rebuilt CoT uses the original time for `time`, `start` and the chat `remarks time`, but
    stale counts from arrival.** A 20-minute-old chat with a 5-minute stale time measured from
    when it was written would arrive already stale.
78. **Relays never change protection.** An encrypting relay passes on only encrypted traffic
    and a clear relay only clear traffic, so turning on relaying can never leak an encrypted
    message in the clear (44).

## Emergency alerts

79. **Alerts are their own message type (4), not a marker.** ATAK treats `b-a-*` events
    specially (alarm, banner), and a cancel must reliably replace the alert, so they get a
    dedicated body. Adding a type does not bump the version: old receivers drop unknown types
    (PROTOCOL.md 8.3). ATAK's `<uid>-9-1-1` convention is not sent twice.
80. **The author repeats an active alert every 30 s** with the latest position until the client
    cancels it, and sends the cancel three times. One transmission can be lost; an emergency
    must not be. An alert the client has not reported for 30 minutes is no longer sent, so a
    crashed or disconnected client does not leave a beacon running forever. At about 0.7 s per
    copy this is roughly 2% of the channel.
81. **Alerts go before all other traffic** in the transmit queue and in store and forward.
82. **Relays pass alerts on** (owner's decision, recorded in AGENTS.md), under the existing
    single-hop rules and limits. The author's own repeats keep relays quiet once everyone near
    the relay has heard the author.
83. **The author's repeats are delivered to ATAK every time**, unlike other repeats, so the alert
    stays fresh; rebuilt alerts are stale after 10 minutes. A cancel is a newer version of its
    alert, so an old relayed copy can never bring a canceled alert back (75).
84. **An unknown alert kind decodes as Custom** instead of being dropped, unlike unknown CoT
    types (18): showing a slightly wrong alert beats losing one.

## FX.25

85. **FX.25 is always received and optional on transmit (off by default).** It costs air time
    (about 1.2x to 1.7x for a position report with the default TXDELAY), and plain receivers
    still read FX.25 frames, so a mixed fleet works and each operator can decide.
86. **Compatible with Dire Wolf**: same correlation tags, Reed-Solomon parameters (Karn's
    library with 0x11D, fcr 1, prim 1), data layout and flag padding, and the same 8-bit tag
    tolerance. Interoperability with a real Dire Wolf has not been tested on the air yet.
87. **The Reed-Solomon code is written in Kotlin in `:core`**, not taken from a library, to keep
    the module dependency-free and unit-tested like the rest of the modem.
88. **A correction in the unsent zero part of a shortened block rejects the block**: it can only
    be a miscorrection.

## Channel health

89. **Statistics are kept for 10 minutes in 10-second buckets**, so old activity falls away
    smoothly. Channel busy is sampled from DCD by the 10 Hz status thread; time spent
    transmitting counts as neither busy nor idle.
90. **Per-station loss comes from a one-byte report counter** in the PLI extension (bit 5). It
    costs 2 bytes per report; old receivers ignore it. A gap over 32 is treated as a restart or
    long absence rather than loss. Only position reports carry it, because chat must keep its
    exact text across repeats (76) and the other types are too irregular to measure by.
91. **"Repaired by FX.25" counts only frames the plain decoders did not get.** An intact frame
    is decoded at its closing flag, before the check bytes arrive, so the count is honest.

## Team profiles

92. **A profile is a `takwave://profile?...` link**, the same text in a QR code, a file or the
    clipboard, with one parser. It carries only settings the team must share; never keys,
    passphrases, radio-specific settings or the relay switch.
93. **Nothing is applied without confirmation.** Links come from outside the app, so the dialog
    lists every change, warns when encryption would be turned on, and applies only on "Apply".
    Any invalid value rejects the whole profile; unknown keys are ignored for forward
    compatibility; a newer version is refused.
94. **QR codes are drawn with ZXing core (Apache-2.0)**, generation only. Scanning is left to
    the phone's camera app or any QR reader, so the app needs no camera permission or code.
95. **Slot assignments are a table of callsigns**; a station takes its own slot only if it is
    listed, so one profile serves the whole team.

## Store-and-forward persistence

96. **Held messages and the seen-cache are saved every 30 s when they change, and on stop**, in
    app-private no-backup storage, written atomically. Messages are stored in their clear
    over-air encoding, so the file format follows the protocol. They are stored decoded even on
    an encrypting station (a send-only ECIES station could not read them back otherwise); the
    file is as private as the key files next to it.
97. **On restore, held messages are dropped if encryption was switched on or off** since they
    were saved (78), as are kinds now switched off, messages past the hold time and messages
    already repeated enough. The repeat budget counts transmissions from before the restart. A
    damaged file changes nothing.

## Hidden callsign

98. **The callsign moves inside the ciphertext; the AX.25 header carries `TAKWV-15`.** The
    placeholder is the same for every station, so the header says nothing about who sent a
    frame, while the destination stays `TAKWV` so receivers and TNCs still find TAKwave traffic.
    A per-frame random source was considered and rejected: it hides nothing more and makes logs
    harder to read. The inner address is authenticated by the AEAD tag; the outer one never was.
99. **Hidden-source packets are protocol version 1; everything else stays version 0.** Putting
    the address in front of the body changes the position of existing fields (PROTOCOL.md 8,
    rule 4). A flag bit was not an option: version-0 receivers mask the type byte, would decrypt
    successfully and could misparse the address as a body. A version they do not implement is
    dropped cleanly. Senders keep using version 0 when not hiding, so mixed fleets keep working.
100. **Encrypting stations always keep 8 bytes of a chat free for the address**, hiding or not,
    so a hiding relay can pass on any encrypted chat with exactly the same text (the rule the
    3-byte extension reserve already follows).
101. **Hidden-source traffic is relayed only by a relay that also hides**, so a relay never
    shows less protection than the author chose. Held messages are dropped on restart when
    hiding was switched (the saved protection byte is 0 clear, 1 encrypted, 2 hidden; 0 and 1
    read the same as the boolean older versions wrote, so no state version bump).
102. **No automatic clear identification.** Sending a periodic clear ID frame would undo the
    point of the feature; station identification is left to the operator, with a warning in the
    settings and in the help text.

## TAK server port

103. **The local TAK server defaults to port 8099, and 8087 is refused.** ATAK ships a built-in
    TCP CoT input on 8087 (TAKwave's default up to 0.3.0). With both on 8087, ATAK could end up
    connected to its own input, which never answers `t-x-c-t` pings, so ATAK dropped and
    re-opened the link every ~30 s even with the gateway stopped; or TAKwave could not bind at
    all, depending on which app started first. A stored 8087 is read as the new default (every
    settings write stores every field, so a deliberate 8087 cannot be told apart from the old
    default, and it is broken either way). Users must re-import the data package once.

## Radio guide

104. **The radio guide gives advice and never changes the radio or the gateway.** TAKwave has
    no CAT control of frequency, so the guide tells the operator what to set. Its choices
    (service, band, width, terrain) are stored in Settings, but the gateway does not read them.
    The only TAKwave setting it touches is FX.25, and only when the operator taps the button.
    Applying FX.25 is safe because receivers always decode it.
105. **The band plan data lives in `:core` (`radio/RadioGuide.kt`) with unit tests**, like the
    other protocol and scheduling logic, so the frequency lists are checked: every suggestion is
    in its band, and none falls on APRS, a calling frequency, MURS, GMRS/FRS, or a non-itinerant
    or 6 kHz Part 90 channel.
106. **US rules only.** Business channels are the nationwide itinerant channels from 47 CFR
    90.35 (footnotes 10 and 17). 151.955 is left out because it is not itinerant, and 151.640
    because it is limited to 6 kHz. Business band is fixed at 12.5 kHz because of the Part 90
    narrowbanding rule. Amateur frequencies follow the ARRL band plan. 70 cm has no nationwide
    packet channel, so the guide names the 445-447 MHz local-option segment and defers to the
    local coordinator. Other countries' band plans are not modeled, and the screen says so.
107. **The data deviation target is 60% of the channel's peak deviation** (3 kHz wide, 1.5 kHz
    narrow). This matches the wide-channel figure the calibration screen already used.

## KISS over Bluetooth

108. **A radio with a built-in KISS TNC is its own modem backend (`KISS_BLUETOOTH`),** not a
    Bluetooth PTT option. Radios such as the BTECH UV-PRO offer the TNC on a Bluetooth serial
    (SPP) port and do the modulation, channel access and keying themselves, so no audio path
    or PTT is involved, and such radios usually accept only one serial connection.
109. **KISS runs over any byte stream.** `KissStreamTransport` in `:core` owns framing,
    reconnecting and status reporting and is given a function that opens a `KissLink`;
    `KissTcpTransport` is that with a TCP socket, and `:app` supplies the Bluetooth link. The
    logic stays unit-tested without Android.
110. **Same Bluetooth rules as PTT (65):** only an explicitly chosen, already-paired device,
    so no scan or location permission. Without a chosen device the backend does not start.
111. **A dropped link is retried every 5 s and frames are refused meanwhile,** rather than
    disabling transmitting until a restart as Bluetooth PTT does (66). The difference is
    deliberate: here the TNC keys the radio, a frame cut off mid-way is never sent, and
    nothing can be left keyed by TAKwave. The Status screen shows "(not connected)".
112. **The standard SPP service UUID is used.** Not verified against a real UV-PRO; if a radio
    offers KISS on a different service, the connect step is the place to change (TODO.md).
