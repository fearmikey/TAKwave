# TAKwave over-air protocol

Protocol version: **0**

This document defines everything TAKwave puts on the air. The reference implementation is
`core/src/main/kotlin/com/fearmikey/takwave/core/codec/PacketCodec.kt`.

## 1. Physical and link layer

| Layer      | Value |
|------------|-------|
| Modulation | Bell 202 AFSK: mark 1200 Hz, space 2200 Hz, 1200 baud, NRZI (a 0 bit is a tone change) |
| Framing    | HDLC: `0x7E` flags, zero-bit stuffing after five 1 bits, bytes sent least significant bit first |
| Check      | 16-bit FCS, CRC-16-CCITT (X.25: reflected polynomial `0x8408`, init `0xFFFF`, final XOR `0xFFFF`), low byte first |
| Frame      | AX.25 UI frame (control `0x03`), PID `0xF0` (no layer 3) |
| Preamble   | Flags for the TXDELAY period (default 300 ms), then one frame, then flags for TXTAIL (default 30 ms) |

This is the same physical layer as 1200-baud APRS, so any hardware or software TNC (for example
Dire Wolf over KISS) can carry TAKwave frames.

### AX.25 addressing

| Field       | Value |
|-------------|-------|
| Destination | `TAKWV` (SSID 0) |
| Source      | the operator's callsign and SSID; this is the legal station identification |
| Digipeaters | none are sent; received frames with a digipeater path are accepted |

The AX.25 header is always sent in the clear, including when payload encryption is on.

One TAKwave message is carried per frame. The information field is at most **256 bytes**, the
AX.25 default, so that hardware TNCs can pass it.

## 2. Payload header

All multi-byte integers are **big-endian**.

| Offset | Size | Field |
|--------|------|-------|
| 0      | 1    | Magic and version: `0x54 + version`. Version 0 is `0x54` (ASCII `T`). Versions 0-15 map to `0x54`-`0x63`. |
| 1      | 1    | Type and flags (below) |
| 2      | n    | Body |

Byte 1:

| Bits | Meaning |
|------|---------|
| 7    | `ENC`: the packet is encrypted (section 6) |
| 6-4  | cipher suite (section 6) when `ENC` is set; otherwise sent as 0 and ignored on receive |
| 3-0  | message type: 1 = PLI, 2 = Chat, 3 = Marker. In an encrypted packet these bits are 0 and the real type is inside the ciphertext. |

## 3. Field encodings

### String
One length byte followed by that many bytes of UTF-8. Each string has a hard cap (below). A
sender truncates on a code-point boundary. A receiver rejects the packet if the length exceeds
the cap, runs past the end of the packet, or the bytes are not valid UTF-8.

### Identifier (uid, message id)
One kind byte, then:

| Kind | Following bytes | Meaning |
|------|-----------------|---------|
| 0    | String, cap 48  | any other identifier, verbatim |
| 1    | 8 bytes         | `ANDROID-` followed by those bytes as 16 lower-case hex digits (the ATAK default uid) |
| 2    | 16 bytes        | a UUID in canonical lower-case form `8-4-4-4-12` |
| 3    | 16 bytes        | a UUID in canonical upper-case form |

### CoT type
One code byte. Code 0 is followed by a String (cap 32) holding the literal type. Codes 1 and up
index this table (append-only):

| Code | Type | Code | Type | Code | Type |
|------|------|------|------|------|------|
| 1 | `a-f-G-U-C` | 9 | `a-h-A` | 17 | `b-m-p-w` |
| 2 | `a-f-G-U-C-I` | 10 | `a-n-A` | 18 | `b-m-p-w-GOTO` |
| 3 | `a-f-G-E-V-C` | 11 | `a-u-A` | 19 | `b-m-p-c` |
| 4 | `a-f-G` | 12 | `a-f-S` | 20 | `b-m-p-s-p-i` |
| 5 | `a-h-G` | 13 | `a-h-S` | 21 | `b-m-p-s-p-loc` |
| 6 | `a-n-G` | 14 | `a-n-S` | 22 | `b-m-p-s-p-op` |
| 7 | `a-u-G` | 15 | `a-u-S` | | |
| 8 | `a-f-A` | 16 | `b-m-p-s-m` | | |

### Position
* Latitude: int32, degrees x 1e7, valid range +/-900000000.
* Longitude: int32, degrees x 1e7, valid range +/-1800000000.
* HAE: int16, metres above the WGS-84 ellipsoid. `-32768` means unknown.

A receiver rejects packets whose latitude or longitude is out of range.

### Team (1 byte)
0 unknown, 1 White, 2 Yellow, 3 Orange, 4 Magenta, 5 Red, 6 Maroon, 7 Purple, 8 Dark Blue,
9 Blue, 10 Cyan, 11 Teal, 12 Green, 13 Dark Green, 14 Brown. Unlisted codes decode as unknown.

### Role (1 byte)
0 unknown, 1 Team Member, 2 Team Lead, 3 HQ, 4 Sniper, 5 Medic, 6 Forward Observer, 7 RTO, 8 K9.
Unlisted codes decode as unknown.

## 4. Message bodies

### 4.1 PLI (type 1)

| Field    | Encoding |
|----------|----------|
| uid      | Identifier |
| type     | CoT type |
| callsign | String, cap 20 (the TAK callsign, not the ham callsign) |
| lat      | int32 |
| lon      | int32 |
| hae      | int16 |
| ce       | uint16, circular error in metres; `0xFFFF` unknown |
| speed    | uint16, units of 0.1 m/s; `0xFFFF` unknown |
| course   | uint16, whole degrees 0-359; any value of 360 or more means unknown (sent as `0xFFFF`) |
| team     | 1 byte |
| role     | 1 byte |
| battery  | 1 byte, percent 0-100; any larger value means unknown (sent as `0xFF`) |
| interval | 1 byte, the sender's base reporting interval in units of 5 s; 0 (or 255) unknown |

A typical PLI (ATAK uid, 4-character callsign) is 37 bytes of payload including the header, and
stays at or under 50 bytes with a UUID uid and an 8-character callsign.

### 4.2 Chat (type 2)

| Field           | Encoding |
|-----------------|----------|
| message id      | Identifier |
| sender uid      | Identifier |
| sender callsign | String, cap 20 |
| room            | 1 code byte, then: `0` = "All Chat Rooms" (nothing follows); `1` = String cap 32, used as both room id and name; `2` = Identifier (room id; for a direct message, the recipient's uid) then String cap 32 (room name) |
| text            | String, cap 200 bytes, further reduced by the sender if needed to keep the payload within 256 bytes |

### 4.3 Marker (type 3)

| Field   | Encoding |
|---------|----------|
| uid     | Identifier |
| type    | CoT type |
| flags   | 1 byte: bit 0 = remarks present, bit 1 = colour present; other bits reserved (0) |
| name    | String, cap 32 |
| lat     | int32 |
| lon     | int32 |
| hae     | int16 |
| remarks | String, cap 80; only if flag bit 0 |
| colour  | int32 ARGB; only if flag bit 1 |

Points only. Shapes, routes, and attachments are never sent.

## 5. Receive rules

* Verify the FCS, that the frame is a UI frame with PID `0xF0`, and that the destination is `TAKWV`.
* Validate every length and range. A malformed packet is logged and dropped; it never reaches a TAK client.
* Ignore frames whose source address equals the station's own.
* Never retransmit anything that was received from RF.
* Trailing bytes after the last defined field are ignored (they are reserved for additive extensions).

## 6. Encryption (Part 90 only)

**Encryption is off by default and is not permitted on amateur radio frequencies.**

When enabled, the payload is:

| Offset | Size | Field |
|--------|------|-------|
| 0      | 1    | `0x54 + version`, in the clear |
| 1      | 1    | `0x80 | (suite << 4)`: ENC set, cipher suite in bits 6-4, type bits 0. In the clear. |
| 2      | n    | suite-specific sealed data (below) |

In every suite the plaintext is one type byte (1, 2, or 3) followed by the message body, and
the additional authenticated data (AAD) is payload bytes 0 and 1. All AEAD tags are 128 bits.

| Suite | Name | Sealed data | Overhead |
|-------|------|-------------|----------|
| 0 | AES-GCM, shared key | nonce (12, random), ciphertext, tag (16) | 28 |
| 1 | ChaCha20-Poly1305, shared key | nonce (12, random), ciphertext, tag (16) | 28 |
| 2 | ECIES X25519 + AES-256-GCM | ephemeral public key (32, raw), ciphertext, tag (16) | 48 |
| 3 | ECIES P-256 + AES-256-GCM | ephemeral public key (33, SEC 1 compressed), ciphertext, tag (16) | 49 |
| 4-7 | reserved | | |

The type byte adds one more byte in every suite. The chat text cap shrinks so the payload
stays within 256 bytes.

### Shared-key suites (0 and 1)

The key is 128, 192 or 256 bits for AES-GCM and 256 bits for ChaCha20-Poly1305. It can come from:

* **A passphrase:** PBKDF2-HMAC-SHA256, salt = the 14 ASCII bytes `TAKwave-PSK-v0`, 100000
  iterations, 256-bit output. The salt is fixed and public so that all stations derive the
  same key, so the passphrase must be long and random. Use ASCII passphrases for
  interoperability.
* **A key file:** 16, 24 or 32 bytes, raw or written as hex or Base64.
* **A static ECDH agreement between two stations:** each side combines its own private key
  with the other's public key (EC P-256 or X25519). The key is HKDF-SHA256 of the shared
  secret with salt `TAKwave-ECDH-v0` and info `pair`, 32 bytes.

### ECIES suites (2 and 3)

Every packet is encrypted to one long-lived "team" public key:

1. The sender generates a fresh ephemeral key pair on the team key's curve.
2. shared = ECDH(ephemeral private, team public).
3. key = HKDF-SHA256(shared, salt = `TAKwave-ECIES-v0`, info = ephemeral public || team
   public), 32 bytes. Public keys are in the on-air encoding: 32 raw bytes for X25519, 33-byte
   compressed point for P-256.
4. The plaintext is sealed with AES-256-GCM using that key and a nonce of twelve zero bytes.
   The key is never used for a second packet, so the fixed nonce is safe.

A station needs only the team public key to send. To read traffic it needs the team private
key. Devices that only report can therefore be deployed without the ability to decrypt.

### General rules

* Keys are loaded from PEM: PKCS#8 (`BEGIN PRIVATE KEY`, unencrypted) and X.509
  SubjectPublicKeyInfo (`BEGIN PUBLIC KEY`). RSA is not supported: an RSA ciphertext is at
  least 256 bytes and cannot fit in a frame.
* A receiver drops an encrypted packet whose suite differs from its own configuration.
* A station with a key still accepts clear packets. A station without the right key drops
  encrypted packets.
* HKDF is RFC 5869 with a single 32-byte output block.
* There is no replay protection beyond the receiver's duplicate suppression, and no forward
  secrecy in the shared-key suites.

## 7. Channel access

Channel access is a station-local matter and puts nothing in the packet, but stations in a
group should agree on it.

### p-persistent CSMA (default)

Wait until no packet is being received (data carrier detect), then transmit with probability
(persistence + 1) / 256, otherwise wait one slot time and repeat. Defaults: slot time 100 ms,
persistence 63.

### Time slots (optional)

UTC time is divided into cycles of `N` slots of `L` milliseconds, aligned to the Unix epoch:

    slot(t) = floor((t mod (N * L)) / L)        t = milliseconds since 1970-01-01T00:00:00Z

A station assigned slot `k` may begin a transmission of duration `d` only when `slot(t) = k`
and the transmission ends at least `G` milliseconds before the slot does (`G` = 250 ms guard).
If `d + G > L` the transmission may begin only within the first 200 ms of the slot. Carrier
sensing still applies inside the slot; there is no random back-off.

Defaults: `L` = 3000 ms, `N` = 8. The default slot is CRC-16-CCITT (as in section 1) of the
upper-case ASCII `CALL-SSID` string (no `-SSID` when the SSID is 0), modulo `N`; because two
callsigns can map to the same slot, groups should assign slots explicitly.

All stations need UTC to within about `G`. Sources: the phone's network-synchronised clock,
or GNSS time.

## 8. Versioning rules

1. The version lives in byte 0. A receiver drops packets whose version it does not implement.
2. Changes that an old receiver can safely ignore do **not** bump the version: appending to the
   CoT type table, the team or role lists, or adding fields to the *end* of a message body.
3. Old receivers treat unknown team and role codes as unknown, but drop packets with unknown CoT
   type codes, identifier kinds, room codes, or message types. Senders should prefer the literal
   forms when talking to a mixed fleet.
4. Anything that changes the meaning or position of an existing field requires a new version.
5. Reserved bits are sent as 0 and ignored on receive.
