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
16. **Markers carry an optional ARGB colour** in addition to the spec's fields, because spot-map
    markers are identified by colour in ATAK.
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
    material is missing or unusable, transmitting is disabled and the Status tab says why.
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
55. **Settings are read when the gateway starts.** The Settings tab offers a restart button.
56. **Audio capture prefers the `UNPROCESSED` source,** falling back to `VOICE_RECOGNITION`, to
    keep AGC and noise suppression away from the modem signal.
57. **Audio devices are remembered by type and product name,** since Android device ids change
    on every reconnect. If the chosen device is absent the default is used, with a warning.
58. **When serial PTT is selected but unavailable, transmitting is disabled** instead of
    playing audio into an unkeyed radio.
59. **The test PLI uses uid `TAKWAVE-<callsign>`** and the last local position, or 0,0.
60. **Dark theme only,** and no icon font dependency: navigation is a text tab row.
61. **At most 8 TAK clients;** a client that stops reading is disconnected once 256 events
    are queued for it.

## Process

62. **`AGENTS.md` was adapted from the owner's Garage project** when the spec referred to a
    file that was not yet in this repository.
63. **Commits are made per phase with no AI attribution; nothing is pushed or tagged.**
