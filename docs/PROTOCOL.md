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
| 6-4  | reserved, sent as 0, ignored on receive |
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
| 1      | 1    | `0x80` (ENC set, type bits 0), in the clear |
| 2      | 12   | random nonce |
| 14     | n+1  | AES-256-GCM ciphertext of: one type byte (1, 2, or 3) followed by the message body |
| 15+n   | 16   | GCM authentication tag |

* Cipher: AES-256-GCM, 96-bit nonce, 128-bit tag.
* Additional authenticated data: payload bytes 0 and 1.
* Key: PBKDF2-HMAC-SHA256 of the passphrase (UTF-16 characters as passed to the platform
  `PBEKeySpec`; use ASCII passphrases for interoperability), salt = the 14 ASCII bytes
  `TAKwave-PSK-v0`, 100000 iterations, 256-bit output.
* The salt is fixed and public so that all stations derive the same key. The passphrase must
  therefore be long and random.
* Encryption adds 29 bytes. The chat text cap shrinks to keep the payload within 256 bytes.
* A station with a key still accepts clear packets. A station without the right key drops
  encrypted packets.
* There is no replay protection beyond the receiver's duplicate suppression.

## 7. Versioning rules

1. The version lives in byte 0. A receiver drops packets whose version it does not implement.
2. Changes that an old receiver can safely ignore do **not** bump the version: appending to the
   CoT type table, the team or role lists, or adding fields to the *end* of a message body.
3. Old receivers treat unknown team and role codes as unknown, but drop packets with unknown CoT
   type codes, identifier kinds, room codes, or message types. Senders should prefer the literal
   forms when talking to a mixed fleet.
4. Anything that changes the meaning or position of an existing field requires a new version.
5. Reserved bits are sent as 0 and ignored on receive.
