// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ui

/** Plain-language explanations shown by the info buttons in the setup guide and in Settings. */
object HelpText {
    const val MICROPHONE =
        "TAKwave hears the radio through an audio input: a USB sound card, a cable, or the phone's own microphone. " +
            "Android calls all of these \"microphone\" access.\n\n" +
            "The audio is decoded on the phone and never recorded or sent anywhere. Without this permission the " +
            "software modem cannot receive, so the gateway only works with an external KISS TNC."

    const val NOTIFICATIONS =
        "While the gateway runs, Android requires a permanent notification. It shows whether TAKwave is idle, " +
            "receiving or transmitting, and has a Stop button.\n\n" +
            "If you refuse, the gateway still runs, but you will not see that notification."

    const val NEARBY_DEVICES =
        "Android asks for the \"Nearby devices\" permission before an app may talk to a paired Bluetooth device.\n\n" +
            "TAKwave only uses it for Bluetooth PTT: sending the key and unkey commands to your radio or adapter. " +
            "It never scans for devices. Skip this if you do not use Bluetooth PTT."

    const val BATTERY =
        "Android puts apps to sleep when the screen is off to save battery. For TAKwave that means missed packets " +
            "and late position reports.\n\n" +
            "Allowing it to ignore battery optimisation keeps the gateway running in your pocket. It does use more " +
            "battery while the gateway is started; nothing runs when it is stopped."

    const val CALLSIGN =
        "Your callsign is your station identification. It is sent in the clear in every packet, and TAKwave refuses " +
            "to transmit without one.\n\n" +
            "Use the callsign from your licence: 1 to 6 letters and digits.\n\n" +
            "The SSID (0 to 15) tells apart several stations that share one callsign, for example your handheld (7) " +
            "and your vehicle (9). If you only run one station, leave it at 0."

    const val RADIO_SETUP =
        "TAKwave needs two things from the radio: an audio path in both directions, and a way to switch it to " +
            "transmit (PTT, \"push to talk\").\n\n" +
            "• USB interface: a box such as a Digirig that is a USB sound card and keys the radio through a serial " +
            "control line. The most reliable choice.\n\n" +
            "• Audio cable with VOX: the radio keys itself when it hears audio. Simple, but the start of a packet " +
            "can be cut off.\n\n" +
            "• Bluetooth serial PTT: audio still goes through a cable or USB sound card, but the radio is keyed by " +
            "a command sent over a Bluetooth serial link, for example Bluetooth CAT control.\n\n" +
            "• External KISS TNC: a separate modem (hardware, or software such as Dire Wolf) does everything; " +
            "TAKwave only exchanges packets with it over the network.\n\n" +
            "• No radio: two phones on a table talk to each other through speaker and microphone. Good for a " +
            "first test."

    const val BACKEND =
        "The modem turns packets into sound and back.\n\n" +
            "Soft modem: TAKwave does it itself, using an audio device connected to the radio. This is what most " +
            "people want.\n\n" +
            "KISS over TCP: an external TNC or a program such as Dire Wolf does the modulation, channel access " +
            "and PTT. TAKwave just hands it packets over a network connection."

    const val AUDIO =
        "Input is the sound coming from the radio's speaker or data port. Output is the sound going to the " +
            "radio's microphone or data port.\n\n" +
            "A USB interface such as a Digirig appears as \"USB audio\"; choose it for both. If you leave both on " +
            "\"System default\" with nothing plugged in, TAKwave uses the phone's own microphone and speaker.\n\n" +
            "TAKwave remembers the device by name, so plug the interface in before choosing it."

    const val PTT =
        "PTT (push to talk) is how TAKwave switches the radio to transmit.\n\n" +
            "Serial (RTS/DTR): a control line on a USB serial adapter closes the radio's PTT contact. Used by the " +
            "Digirig and most USB interfaces.\n\n" +
            "Bluetooth serial: a command is sent to the radio or adapter over a Bluetooth serial link.\n\n" +
            "VOX: the radio keys itself when it hears audio. Turn VOX on in the radio's menu.\n\n" +
            "None: nothing is keyed. Only useful for tests.\n\n" +
            "Whatever the method, TAKwave releases PTT after every transmission and a watchdog forces it off if " +
            "a transmission runs too long."

    const val SERIAL_PTT =
        "Pick the USB serial adapter that is wired to your radio's PTT, and allow TAKwave to use it when " +
            "Android asks.\n\n" +
            "Control line: which pin keys the radio. The Digirig uses RTS. Some home-made and older interfaces " +
            "use DTR.\n\n" +
            "Invert polarity: leave off unless your radio transmits all the time and stops when TAKwave tries to " +
            "send; then the interface keys on the opposite level.\n\n" +
            "Turn the radio's own VOX off when using serial PTT."

    const val BLUETOOTH_PTT =
        "TAKwave connects to a Bluetooth device that offers a serial port (SPP) and sends one command to start " +
            "transmitting and another to stop.\n\n" +
            "Pair the radio or adapter in Android's Bluetooth settings first, then choose it here. Pick the " +
            "command set your radio understands, or enter your own. In custom commands, write special bytes as " +
            "\\r, \\n or \\xHH, for example \\xFE\\xFE\\x94\\xE0\\x1C\\x00\\x01\\xFD for an Icom radio at address 94.\n\n" +
            "Only the PTT goes over Bluetooth; the audio still needs a cable or USB sound card.\n\n" +
            "Radios that only pair with a Bluetooth earpiece or a Bluetooth PTT button cannot be keyed this way: " +
            "they expect a headset, and Android does not let an app act as one.\n\n" +
            "Safety: if the Bluetooth link drops in the middle of a transmission, the stop command cannot reach " +
            "the radio. Set the radio's own time-out timer (TOT) to a short value as a backstop."

    const val KISS =
        "Enter the address and port where the TNC accepts KISS connections. Dire Wolf on this phone or on a " +
            "computer listens on port 8001 by default; use 127.0.0.1 when it runs on this phone.\n\n" +
            "The TNC is responsible for audio levels, PTT and channel access, so TAKwave's audio, PTT and " +
            "transmit settings are not used."

    const val TRANSMIT =
        "TXDELAY is the pause between keying the radio and sending data. Radios need a moment to reach full " +
            "power, and the receiving radio needs a moment to open its squelch. 300 ms suits most radios with " +
            "serial or Bluetooth PTT; with VOX use 500 ms or more. Raise it if the other station misses your " +
            "packets; lower it to save air time.\n\n" +
            "TXTAIL keeps the transmitter keyed for a moment after the data so the end of the packet is not cut off.\n\n" +
            "TX audio level sets how loud the tones are. Too loud distorts and is the most common reason packets " +
            "do not decode. Fine-tune it later on the Calibrate tab while listening on a second radio.\n\n" +
            "TX timeout is a safety limit: the transmitter is forced off if one transmission lasts longer."

    const val TAK_SERVER =
        "ATAK connects to TAKwave as if it were a TAK server running on this phone. The port must match the " +
            "one in ATAK; the exported data package sets it for you.\n\n" +
            "\"Allow LAN clients\" lets a TAK client on another device connect, for example over this phone's " +
            "hotspot. There is no password, so only turn it on for a network you control."

    const val PLI =
        "PLI (position location information) is your marker on everyone else's map.\n\n" +
            "The channel is slow, so TAKwave sends your position at the base interval, and sooner if you have " +
            "moved or turned by the amounts below, but never more often than the minimum interval. Longer " +
            "intervals leave more air time for the rest of the team."

    const val CSMA =
        "Before transmitting, TAKwave listens and waits until nobody else is sending. Then it rolls a dice " +
            "every \"slot time\" and transmits with a chance of (persistence + 1) / 256, so that several waiting " +
            "stations do not all start at once.\n\n" +
            "The defaults (100 ms, 63) suit a small team. Lower the persistence for a busy channel.\n\n" +
            "The input level threshold also treats voice or noise as \"busy\". Leave it at 0 for acoustic tests."

    const val SLOTS =
        "Carrier sensing fails when two stations cannot hear each other but a third hears both: they transmit " +
            "at the same time and neither gets through.\n\n" +
            "With time slots, every station gets its own turn in a repeating cycle, timed from the clock. Everyone " +
            "in the group must use the same slot length and slot count and a different slot number, and all " +
            "clocks must be right to within about a quarter of a second."

    const val STORE_FORWARD =
        "Store and forward helps teammates who were out of range when a chat message or marker was sent.\n\n" +
            "Send my messages again: TAKwave keeps your own chat messages and markers for a while. When it hears a " +
            "station that was not in range when you sent one, it sends that message once more. Stations that already " +
            "have it do not show it twice. A direct message is only sent again for its recipient.\n\n" +
            "Relay for other stations: TAKwave also passes on chat, markers and emergency alerts it hears from others, under your " +
            "callsign. Turn this on for one well-placed station, such as a vehicle or a hilltop, not for everyone. " +
            "A relayed message is marked as such and is never relayed a second time, so it cannot loop.\n\n" +
            "Position reports are never held or relayed: an old position would put a marker in the wrong place.\n\n" +
            "The limits keep repeats from crowding out new traffic. Each chat message takes one to two seconds of " +
            "air time, so keep the repeats per 10 minutes low on a busy channel. New messages always go first.\n\n" +
            "Every relayed packet carries your callsign as the station identification. Check that automatic " +
            "relaying is allowed by the rules for your licence and band before turning it on."

    const val FX25 =
        "FX.25 adds error-correction bytes to each packet, so a packet that arrives with a few damaged bytes " +
            "can be repaired instead of lost. It helps most on weak or noisy links.\n\n" +
            "TAKwave always understands FX.25 when it receives it. This setting only decides whether your own " +
            "packets are sent with it. Stations without FX.25 still read them, because the normal packet is " +
            "inside unchanged; they just cannot repair it.\n\n" +
            "The cost is air time: with the default TXDELAY a position report takes about 1.2 times as long with " +
            "16 check bytes and about 1.7 times with 64. More check bytes repair more damage and take longer. " +
            "Very long chat messages do not fit an FX.25 block and are " +
            "sent as normal packets.\n\n" +
            "With a KISS TNC, FX.25 is set up in the TNC (in Dire Wolf: FX25TX), not here."

    const val TEAM_PROFILE =
        "A team profile carries the settings everyone in a group should share: position report pacing, " +
            "channel access, time slots, store-and-forward limits, FX.25, and the encryption mode.\n\n" +
            "It never contains keys or passphrases, or settings that belong to one radio (audio devices, PTT, " +
            "TXDELAY, levels). Load keys separately.\n\n" +
            "Slot assignments list a slot number per callsign. A station that finds its own callsign in the " +
            "list takes that slot; others keep their own setting.\n\n" +
            "Share the profile as a QR code (scan it with the camera app on the other phone, or with any QR " +
            "reader and paste the text into TAKwave), as a file, or as text. TAKwave always shows what would " +
            "change and asks before applying a profile."

    const val CHANNEL_HEALTH =
        "How busy the radio channel has been over the last 10 minutes.\n\n" +
            "Channel busy is the share of time a packet was being received. Above about half, packets start to " +
            "collide: lengthen the position report interval or turn on time slots.\n\n" +
            "My air time is how long this station transmitted. One station using more than a fifth of the " +
            "channel crowds the others out.\n\n" +
            "Repaired by FX.25 counts packets that would have been lost without error correction. Other traffic " +
            "is packet radio that is not TAKwave (for example APRS). Unreadable means TAKwave packets that " +
            "could not be decoded, often a key or encryption mismatch.\n\n" +
            "Under Heard, the percentage after a station is the share of its position reports that arrived here."

    const val EMERGENCY =
        "When someone raises an alert in ATAK (911, Ring The Bell, In Contact), TAKwave sends it ahead of " +
            "all other traffic and repeats it every 30 seconds with the latest position until it is cancelled " +
            "in ATAK. The cancel is sent three times.\n\n" +
            "Alerts heard from other stations appear here and in ATAK. Relay stations pass alerts on like chat."

    const val ENCRYPTION =
        "Encryption hides the content of your messages from anyone without the key. Your callsign is still sent " +
            "in the clear.\n\n" +
            "It is NOT permitted on amateur radio frequencies. Only turn it on if you operate under a licence " +
            "that allows it, such as Part 90 business band.\n\n" +
            "Every station must use the same mode and keys. If encryption is on but the key is missing, TAKwave " +
            "stops transmitting instead of sending in the clear."
}
