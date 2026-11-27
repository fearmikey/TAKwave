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
            "TAKwave only uses it for Bluetooth PTT (sending the key and unkey commands to your radio or adapter) " +
            "and for a radio with a built-in Bluetooth TNC (exchanging packets with it). It never scans for " +
            "devices. Skip this if you use neither."

    const val BATTERY =
        "Android puts apps to sleep when the screen is off to save battery. For TAKwave that means missed packets " +
            "and late position reports.\n\n" +
            "Allowing it to ignore battery optimization keeps the gateway running in your pocket. It does use more " +
            "battery while the gateway is started; nothing runs when it is stopped."

    const val CALLSIGN =
        "Your callsign is your station identification. It is sent in the clear in every packet, and TAKwave refuses " +
            "to transmit without one.\n\n" +
            "Use the callsign from your license: 1 to 6 letters and digits.\n\n" +
            "The SSID (0 to 15) tells apart several stations that share one callsign, for example your handheld (7) " +
            "and your vehicle (9). If you only run one station, leave it at 0."

    const val RADIO_SETUP =
        "TAKwave needs two things from the radio: an audio path in both directions, and a way to switch it to " +
            "transmit (PTT, \"push to talk\").\n\n" +
            "• USB interface: a box such as a Digirig that is a USB sound card and keys the radio through a serial " +
            "control line. The most reliable choice.\n\n" +
            "• Audio cable with VOX: the radio keys itself when it hears audio. Simple, but the start of a packet " +
            "can be cut off. Auto-PTT cables such as the BTECH APRS-K1 PRO belong here too: they are a USB sound " +
            "card that keys the radio when TAKwave plays audio, so the radio's own VOX stays off.\n\n" +
            "• Bluetooth serial PTT: audio still goes through a cable or USB sound card, but the radio is keyed by " +
            "a command sent over a Bluetooth serial link, for example Bluetooth CAT control.\n\n" +
            "• Radio with built-in Bluetooth TNC: radios such as the BTECH UV-PRO contain their own packet modem. " +
            "TAKwave exchanges packets with it over Bluetooth, with no cables at all.\n\n" +
            "• External KISS TNC: a separate modem (hardware, or software such as Dire Wolf) does everything; " +
            "TAKwave only exchanges packets with it over the network.\n\n" +
            "• P25 radio with packet data: a digital radio such as a Motorola APX 8000, XTS 2500 or L3Harris " +
            "XL-200P carries the packets with its own P25 modem. TAKwave connects to it over its USB cable or " +
            "serial data cable.\n\n" +
            "• DMR radio (MOTOTRBO or Hytera): a Motorola XPR or SL, or a Hytera PD or HP series radio, carries the " +
            "packets as DMR data calls. TAKwave connects to it over its USB programming cable. Amateur DMR radios " +
            "(AnyTone, Ailunce, TYT) cannot pass data to a phone; use them in analog FM with one of the setups above.\n\n" +
            "• D-STAR or NXDN radio: an Icom D-STAR radio (IC-705, ID-52, IC-9700) in DV data mode, or a Kenwood NX " +
            "or Icom IDAS NXDN radio with transparent data, passes TAKwave's packets through its data port. TAKwave " +
            "connects over USB serial or Bluetooth serial.\n\n" +
            "• No radio: two phones on a table talk to each other through speaker and microphone. Good for a " +
            "first test."

    const val BACKEND =
        "The modem turns packets into sound and back.\n\n" +
            "Soft modem: TAKwave does it itself, using an audio device connected to the radio. This is what most " +
            "people want.\n\n" +
            "KISS over TCP: an external TNC or a program such as Dire Wolf does the modulation, channel access " +
            "and PTT. TAKwave just hands it packets over a network connection.\n\n" +
            "KISS over Bluetooth: the same, but the TNC is inside the radio (for example the BTECH UV-PRO or Kenwood " +
            "TH-D75) or is a Bluetooth TNC, and TAKwave reaches it over classic Bluetooth serial or Bluetooth LE. No " +
            "audio cable is needed.\n\n" +
            "P25 radio packet data: a digital P25 radio with packet data, such as a Motorola APX 8000 or XTS 2500, " +
            "an L3Harris XG or XL series, or a Kenwood NX-5000, carries the packets with its own P25 modem. TAKwave " +
            "talks to it over its USB cable, or its serial data cable through a USB or Bluetooth serial adapter.\n\n" +
            "DMR radio data: a Motorola MOTOTRBO radio (such as an XPR 6550) or a Hytera radio carries the packets " +
            "as DMR data calls to a talkgroup. TAKwave talks to it over its USB programming cable.\n\n" +
            "D-STAR or NXDN radio data: an Icom D-STAR radio in DV data mode, or a Kenwood NX / Icom IDAS NXDN radio " +
            "with transparent data, sends whatever arrives on its data port over the air. TAKwave reaches it over USB " +
            "serial or Bluetooth serial."

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
            "VOX: the radio keys itself when it hears audio. Turn VOX on in the radio's menu. Also use VOX for " +
            "auto-PTT cables such as the BTECH APRS-K1 PRO, which key the radio from the audio and have no serial " +
            "port; with those, leave the radio's VOX off.\n\n" +
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
            "Turn the radio's own VOX off when using serial PTT.\n\n" +
            "If no adapter shows up but the interface works as USB audio, it has no serial port: it is probably an " +
            "auto-PTT cable such as the BTECH APRS-K1 PRO. Choose VOX instead and leave the radio's own VOX off."

    const val BLUETOOTH_PTT =
        "TAKwave connects to a Bluetooth device that offers a serial port (SPP) and sends one command to start " +
            "transmitting and another to stop.\n\n" +
            "Pair the radio or adapter in Android's Bluetooth settings first, then choose it here. Pick the " +
            "command set your radio understands, or enter your own. Ready-made sets: Kenwood / Elecraft CAT, Yaesu " +
            "CAT, Kenwood TH-D74 / TH-D75, and Icom IC-705 CI-V (set the IC-705's Bluetooth serial port function to " +
            "CI-V). In custom commands, write special bytes as \\r, \\n or \\xHH, for example " +
            "\\xFE\\xFE\\x94\\xE0\\x1C\\x00\\x01\\xFD for an Icom radio at address 94.\n\n" +
            "When TAKwave recognises the chosen radio by its Bluetooth name, it says how the radio is best used. " +
            "Some, such as the UV-PRO and TH-D75, have a TNC built in: \"KISS over Bluetooth\" then needs no " +
            "audio cable at all.\n\n" +
            "TAKwave tries the standard serial port with an encrypted link first, then without encryption for " +
            "adapters that refuse it. PTT commands only ever go to the standard serial port.\n\n" +
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

    const val KISS_BLUETOOTH =
        "Some radios, such as the BTECH UV-PRO, Radioddity GA-5WB, Vero VR-N76 and Kenwood TH-D74 / TH-D75, have a " +
            "packet modem (KISS TNC) built in and offer it over Bluetooth. Bluetooth TNCs such as the Mobilinkd do the " +
            "same for any radio they are wired to. TAKwave then needs no audio cable: it hands packets to the TNC, " +
            "which does the modulation, waits for a clear channel and keys the radio.\n\n" +
            "1. Pair the radio or TNC in Android's Bluetooth settings. A Bluetooth LE TNC (such as the Mobilinkd " +
            "TNC4) can instead be found with \"Find a Bluetooth LE TNC nearby\": Android shows the TNCs it finds, " +
            "so TAKwave needs no location or scan permission.\n" +
            "2. Switch the KISS TNC on (in the radio's menu or its companion app), and tune the radio to the " +
            "team's frequency.\n" +
            "3. Choose the radio or TNC here.\n\n" +
            "Bluetooth link: Automatic uses Bluetooth LE for a device that only has that, and otherwise classic " +
            "Bluetooth serial, falling back to Bluetooth LE if that fails. Choose one if you know it. On classic " +
            "serial, TAKwave tries the standard serial port with and without encryption, then any serial service " +
            "of the radio's own.\n\n" +
            "Most radios accept only one Bluetooth serial connection, so close the radio's companion app and any " +
            "other packet app while TAKwave is running. If the link drops, TAKwave reconnects on its own; packets " +
            "are not sent while it is down.\n\n" +
            "The radio's TNC works at 1200 baud without FX.25, which every TAKwave station can read. TAKwave's " +
            "audio, PTT, transmit, FX.25 and time slot settings are not used."

    const val P25_DATA =
        "A P25 radio with packet data can carry TAKwave's packets with its own digital modem: an APX portable " +
            "(APX 6000, APX 8000) on its USB cable, or an ASTRO 25 radio (XTS 2500, XTS 5000, XTL) on its serial " +
            "data cable. TAKwave connects to the radio as its \"mobile computer\", speaks IP to it, and sends each " +
            "packet as a UDP datagram. The radio does the modulation, waits for the channel and keys itself.\n\n" +
            "APX portables: the USB cable makes the radio a small network adapter (RNDIS). Plug it into the " +
            "phone with an OTG adapter; TAKwave offers to use it, asks the radio for the addresses (DHCP), and " +
            "falls back to the addresses set here if the radio does not hand any out.\n\n" +
            "Serial data cable: a USB-to-RS-232 adapter on the radio's RS-232 data cable; TAKwave finds the baud " +
            "rate and PPP or SLIP by itself.\n\n" +
            "Bluetooth: a Bluetooth serial (SPP) adapter on the same RS-232 data cable, so the phone needs no cable. " +
            "Set the adapter to the radio's data port baud rate (8N1) with its own setup tool and pair it in " +
            "Android's Bluetooth settings; TAKwave finds PPP or SLIP by itself, but cannot change the adapter's " +
            "rate. The adapter must pass all 256 byte values (no software flow control).\n\n" +
            "Automatic setup: plugging a Motorola radio in on its USB cable makes TAKwave offer to use it. With " +
            "\"Detect the radio's settings automatically\" on (the default), TAKwave asks an APX for addresses " +
            "(DHCP, sent so it cannot be routed), or finds a serial data port's baud rate and framing by sending a " +
            "PPP link request and a ping that cannot be forwarded (TTL 1), and keeps the addresses the radio agrees to. None of this goes over the air. What it cannot " +
            "find out by itself: whether the radio has the packet data option, the other radios' addresses, and " +
            "the UDP port your team uses.\n\n" +
            "TAKwave cannot see whether the radio transmits. The radio takes each packet on the cable and keys " +
            "itself; if its programming does not let it send the packet, it drops it, at best answering with an " +
            "error that TAKwave shows as a warning. \"LINK UP\" on the Status screen only means the cable link " +
            "works. Watch the radio's transmit indicator when you test.\n\n" +
            "What the radio needs (set in its programming software; this is from community reports and one field " +
            "test: an APX 8000 transmitted TAKwave's packets once its data profile was set as below. Reception by a " +
            "second radio is not confirmed yet):\n" +
            "• The APCO packet data option in the radio's flash (Q947 / W947 on ASTRO 25 radios). Without it the " +
            "radio has no data service.\n" +
            "• APX portables (Data Configuration > Data Profiles): a data profile of type Conventional whose Packet " +
            "Data Mode is Repeated or Direct, not FNE. FNE waits for a data gateway on the channel; without one " +
            "the radio takes TAKwave's packets and never transmits them. The APX 8000 in the field test transmitted " +
            "with Repeated; Direct (radio to radio without a repeater) has not been tried yet. " +
            "Limited Broadcast checked if you send to " +
            "255.255.255.255 (the default here), and Terminal Data checked so the radio passes data to and from the " +
            "phone. The conventional channel used must have packet data on and use that profile.\n" +
            "• ASTRO 25 radios (XTS, XTL), Radio Wide > Data: subscriber IP 192.168.128.1 and mobile computer IP " +
            "192.168.128.2 (the defaults here), and the data port enabled (auto-detect finds its framing and baud " +
            "rate). On the conventional channel used: Packet Data Capable System on, Packet Data Mode \"Repeated\" " +
            "(reported to be needed even on simplex), a Subscriber Air-Interface IP that is different on every radio " +
            "but in the same /24 network, and Limited Broadcast on if you send to 255.255.255.255.\n" +
            "• A trunked system needs data infrastructure (a packet data gateway); conventional and simplex channels " +
            "do not.\n\n" +
            "Cables: for an APX portable, its USB programming/data cable and a USB OTG adapter for the phone. For an " +
            "XTS 2500, a USB-to-RS-232 adapter on the RS-232 data cable (reportedly RKN4106).\n\n" +
            "Other makers (not yet tested with TAKwave): an L3Harris XG-100P, XL-200P or similar with P25 data, a " +
            "Kenwood NX-5000 in P25 mode, or a BK or EF Johnson P25 radio should work the same way if it offers IP " +
            "packet data to a computer on its USB cable (as a network adapter) or on a serial data cable (PPP or " +
            "SLIP). Plugging them in does not open the setup dialog, so choose \"P25 radio packet data\" here and " +
            "pick the radio below. Their cable addresses differ from Motorola's: with auto-detect on TAKwave asks " +
            "the radio, otherwise copy them from the radio's programming. The radio must have its data feature " +
            "enabled and an air-interface IP address of its own.\n\n" +
            "Every station must use the same UDP port. P25 data is slow (a few kilobits per second at best), so " +
            "keep the PLI interval generous. Your callsign still goes inside every packet as the station " +
            "identification. On amateur frequencies, the radio's own encryption must be off as well."

    const val DMR_DATA =
        "A MOTOTRBO radio can carry TAKwave's packets as DMR data: TAKwave connects to the radio over its USB " +
            "programming cable (the radio appears as a small network adapter), speaks IP to it, and sends each " +
            "packet as a UDP datagram. The radio does the modulation, waits for the channel and keys itself.\n\n" +
            "Addresses: MOTOTRBO gives every radio ID and talkgroup an IP address. Talkgroup 1 is 225.0.0.1 and " +
            "radio 1234 is 12.0.4.210. TAKwave sends each packet to the talkgroup as a group data call, which every " +
            "radio on the channel with that talkgroup in its receive group list hears. You can add individual radio " +
            "IDs too, at the cost of one more transmission each.\n\n" +
            "What the radio needs (set in MOTOTRBO CPS; CPS 16 for first-generation radios such as the XPR 6550). " +
            "This comes from Motorola's documentation and community reports and is not yet tested with TAKwave:\n" +
            "• Radio Wide (or General Settings) > Network: \"Forward to PC\" set to Via USB. Without it the radio " +
            "keeps received data to itself.\n" +
            "• Network: Radio IP 192.168.10.1 (the default) and the CAI Network (12) and CAI Group Network (225) " +
            "matching the values here. Every radio needs a different radio ID.\n" +
            "• Network: \"Compressed UDP Data Header\" turned on, with the same choice on every radio (DMR Standard " +
            "if offered, else MOTOTRBO). It shrinks the 28-byte IP header that would otherwise go out with every packet.\n" +
            "• On the channel: the talkgroup used here in the receive group list, and data allowed on that channel " +
            "(on a repeater, data shares the timeslot with voice).\n" +
            "• Leave ARS (automatic registration) off unless your system has a presence server; radio-to-radio data " +
            "does not need it.\n" +
            "• Capacity Plus and Linked Capacity Plus systems need data revert channels; conventional channels and " +
            "simplex do not.\n\n" +
            "Cable: the radio's USB programming cable (PMKN4012 for XPR portables) and a USB OTG adapter for the " +
            "phone. If a computer does not see the radio either, the cable or the radio's side connector is at fault.\n\n" +
            "With \"Get the addresses from the radio\" on (the default), TAKwave asks the radio's DHCP server for " +
            "the addresses, which never goes over the air, and falls back to the addresses set here.\n\n" +
            "DMR data is slow (well under 2 kbit/s per timeslot), so keep the PLI interval generous. Every station " +
            "must use the same UDP port. Your callsign still goes inside every packet as the station identification. " +
            "On amateur frequencies the radio's own privacy (Basic or Enhanced) must be off as well.\n\n" +
            "Hytera radios (PD, HP and X1 series): the USB cable makes them a network adapter too, and their data " +
            "service maps radio IDs to addresses in the same way. In Hytera's programming software, turn on " +
            "forwarding of data to the PC over USB and note the radio's IP network settings; TAKwave starts from " +
            "network 10 for radios and 225 for talkgroups, which is a best guess and not yet tested. Hytera radios " +
            "and MOTOTRBO radios do not exchange data with each other this way.\n\n" +
            "Amateur DMR radios such as the AnyTone AT-D878UV or Ailunce HD1 cannot pass data to a phone, so they " +
            "cannot be used here. They can run TAKwave in analog FM with an audio cable instead."

    const val SERIAL_DATA =
        "Some digital radios send whatever arrives on their data port over the air and deliver it from the data " +
            "port of the other radios. TAKwave uses that as its modem: each packet goes into the port with a check " +
            "value, and the radio keys itself, transmits it and unkeys. Not yet tested with TAKwave; what follows " +
            "comes from the radios' manuals and community reports.\n\n" +
            "Icom D-STAR (IC-705, ID-52, IC-9700, ID-5100 and similar):\n" +
            "• Put the radio in DV mode on the team's frequency (simplex, or a repeater without gateway linking).\n" +
            "• Set the data port to DV Data: on USB this is the USB serial function (on the IC-705, the second " +
            "port, \"USB (B)\"); on Bluetooth (ID-52, IC-705) the Bluetooth data device's serial port function.\n" +
            "• Set DV Data TX to Auto, so the radio transmits when data arrives.\n" +
            "• Match the data port's baud rate here (9600 is common) when using USB.\n" +
            "• DV Fast Data (IC-705, IC-9700, ID-52 and other recent radios) is about three times faster but sends " +
            "data in place of voice; every station must use the same choice. Slow data is about 900 bit/s, so keep " +
            "the PLI interval long.\n" +
            "D-STAR is an amateur mode; your D-STAR callsign goes out in the radio's header, and TAKwave's packets " +
            "carry your callsign too.\n\n" +
            "Kenwood NX and Icom IDAS (NXDN):\n" +
            "• Enable transparent data (Kenwood) or serial data (Icom) on the radio's data port in its " +
            "programming software, at the baud rate set here, on an NXDN conventional channel. Model and option " +
            "support varies; check your radio's manual.\n" +
            "• Connect a USB-to-RS-232 adapter to the radio's data cable, or a Bluetooth serial (SPP) adapter " +
            "wired to that cable.\n\n" +
            "Any of these radios can also be reached through a Bluetooth serial adapter plugged into its data cable. " +
            "All stations on the channel must use the same kind of radio and data mode; D-STAR, NXDN, DMR, P25 and " +
            "analog stations do not hear each other. A packet damaged on the air fails its check and is dropped. " +
            "If every packet arrives damaged, or none arrive although the link is up, switch Encoding to Text: some " +
            "radios change or drop certain byte values, or only pass data line by line. " +
            "FX.25, PTT, audio and channel access settings are not used. On amateur frequencies the radios' own " +
            "scrambling or encryption must stay off."

    const val RADIO_TUNING =
        "These help when a digital radio does not behave as expected. TAKwave's support for these radios was " +
            "written from documentation and has not yet been tested on the air.\n\n" +
            "Pacing: the radio has only a small buffer, so TAKwave waits after each packet for the time the radio " +
            "should take to send it. If the second or third of several packets in a row never arrives, the radio's " +
            "buffer overflowed: lower the data rate or raise the key-up time. If the radio transmits in short " +
            "bursts with long gaps, raise the data rate. The air time figures on the Status screen use the same " +
            "numbers.\n\n" +
            "Raw radio data: logs what reaches the phone but is not a TAKwave packet. For a P25 or DMR radio that is " +
            "every datagram on another port or protocol (the radio's own text messages, location reports, " +
            "registration), with its addresses and first bytes. For a D-STAR or NXDN radio, the raw bytes received " +
            "(at most 20 chunks a minute), damaged packets and text lines that are not TAKwave's. If packets never " +
            "arrive, this shows whether anything reaches the phone at all, and in what form."

    const val TRANSMIT =
        "TXDELAY is the pause between keying the radio and sending data. Radios need a moment to reach full " +
            "power, and the receiving radio needs a moment to open its squelch. 300 ms suits most radios with " +
            "serial or Bluetooth PTT; with VOX use 500 ms or more. Raise it if the other station misses your " +
            "packets; lower it to save air time.\n\n" +
            "TXTAIL keeps the transmitter keyed for a moment after the data so the end of the packet is not cut off.\n\n" +
            "TX audio level sets how loud the tones are. Too loud distorts and is the most common reason packets " +
            "do not decode. Fine-tune it later under Calibrate levels while listening on a second radio.\n\n" +
            "TX timeout is a safety limit: the transmitter is forced off if one transmission lasts longer."

    const val TAK_SERVER =
        "ATAK connects to TAKwave as if it were a TAK server running on this phone. The port must match the " +
            "one in ATAK; the exported data package sets it for you. After changing it, export the data package " +
            "again and remove the old TAKwave server in ATAK. Port 8087 is not allowed: ATAK listens there " +
            "itself and would end up connected to its own input.\n\n" +
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
            "relaying is allowed by the rules for your license and band before turning it on."

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
            "With a digital radio or a TNC that keys itself, TAKwave only knows that a packet was handed over, not " +
            "whether it went out on the air: the sent counts are packets handed to the radio, the air time is an " +
            "estimate, and Channel busy is not measured. A packet the radio reports as undelivered counts as " +
            "failed.\n\n" +
            "Repaired by FX.25 counts packets that would have been lost without error correction. Other traffic " +
            "is packet radio that is not TAKwave (for example APRS). Unreadable means TAKwave packets that " +
            "could not be decoded, often a key or encryption mismatch.\n\n" +
            "Under Heard, the percentage after a station is the share of its position reports that arrived here."

    const val EMERGENCY =
        "When someone raises an alert in ATAK (911, Ring The Bell, In Contact), TAKwave sends it ahead of " +
            "all other traffic and repeats it every 30 seconds with the latest position until it is canceled " +
            "in ATAK. The cancel is sent three times.\n\n" +
            "Alerts heard from other stations appear here and in ATAK. Relay stations pass alerts on like chat."

    const val RADIO_GUIDE =
        "The radio guide suggests a band, channel width and frequencies for your license and terrain, and the " +
            "radio settings that matter for data. TAKwave cannot change the radio itself: set the channel on the " +
            "radio or in its programming software.\n\n" +
            "Amateur (ham): 2 m and 70 cm, under an amateur license. Every packet carries your callsign. Encryption " +
            "is not permitted.\n\n" +
            "Business band: VHF and UHF channels under an FCC Part 90 license. Use only the frequencies and " +
            "emissions on your license. Channels must be 12.5 kHz (narrowband).\n\n" +
            "Channel width must match on every radio in the team. A mismatch distorts or weakens the audio, and " +
            "packets fail to decode.\n\n" +
            "The frequency lists follow US rules and the ARRL band plan. Local band plans and frequency " +
            "coordinators come first, and other countries have their own rules."

    const val ENCRYPTION =
        "Encryption hides the content of your messages from anyone without the key. Your callsign is still sent " +
            "in the clear unless you also turn on 'Encrypt my callsign too'. Then every station's packets carry the " +
            "same placeholder in the radio header and the real callsign travels inside the encrypted part, so " +
            "your station must identify itself some other way.\n\n" +
            "It is NOT permitted on amateur radio frequencies. Only turn it on if you operate under a license " +
            "that allows it, such as Part 90 business band.\n\n" +
            "Every station must use the same mode and keys. If encryption is on but the key is missing, TAKwave " +
            "stops transmitting instead of sending in the clear."
}
