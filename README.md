# EnigmaCast

EnigmaCast turns an Android TV into a remote screen for an Enigma2 receiver. The receiver stays the GUI. The TV plays the live MPEG-TS stream with Media3 and draws the real Enigma2 OSD, grabbed from OpenWebif, on top of it. The Android TV remote sends Linux key codes to OpenWebif.

The first target is an Octagon SF8008 running OpenATV 7.6. Nothing in the app is hardcoded to that model. The same OpenWebif client is meant for other Enigma2 receivers (Vu+, Dreambox, Zgemma, Mutant, Gigablue, Edision, and others) as long as they expose OpenWebif and the standard streaming port.

```text
Enigma2 receiver
    live MPEG-TS :8001 -----------------> Media3 / ExoPlayer ----+
    /grab?format=png&mode=osd ----------> OSD overlay -----------+--> Android TV
    /api/remotecontrol <----------------- DPAD / remote keys <---+
    /api/getcurrent --------------------> service monitor -------+--> new stream URL
```

The full-screen player still shows the receiver's own OSD. The dashboard adds a local channel list, EPG, Search and My Items so you can browse, zap, search and play recordings without opening that player.

That is the "Follow receiver" mode. The default mode is "Own tuner": each Android TV streams its own channel straight from the receiver without zapping it, so several TVs can watch different channels at once, one per free tuner. See [Viewing modes](#viewing-modes).

## Requirements

- Android Studio Quail 4 (2026.1.4) or newer, with AGP 9.4 support
- JDK 17 or newer. Android Studio's bundled JDK 21 is the one used for development
- Android SDK platform 37 and build-tools. Gradle downloads platform 37 when the SDK licenses are accepted
- An Android TV device or an Android TV emulator. `minSdk` is 28 (Android 9). Android 10, 11, and newer are included.
- A receiver on the same LAN with OpenWebif enabled and the stream server on port 8001

`compileSdk` and `targetSdk` are 37 because Compose 1.12 and AndroidX Hilt 1.4 require compile SDK 37. Android 17 ignores a forced landscape lock on large screens. The activity still requests landscape, and the UI is a fullscreen 16:9 surface, which is how Android TV presents the app.

## Dependency versions

Pinned in `gradle/libs.versions.toml` (stable releases checked on 5 October 2026):

| Component | Version |
| --- | --- |
| Kotlin | 2.4.20 |
| KSP | 2.3.11 |
| Android Gradle Plugin | 9.4.1 |
| Gradle | 9.7.0 |
| Compose BOM | 2026.09.00 |
| Compose for TV | 1.1.0 |
| Media3 | 1.11.1 |
| Dagger Hilt | 2.60.1 |
| AndroidX Hilt | 1.4.0 |
| OkHttp | 5.5.0 |
| Coil | 3.6.0 |
| DataStore | 1.2.1 |
| kotlinx-coroutines / serialization | 1.11.0 |
| compileSdk / targetSdk | 37 |
| minSdk | 28 |
| applicationId | com.enigmacast.app |

Gradle 9.8.0 exists, but Kotlin 2.4.20 documents full compatibility through 9.7.0, so the wrapper stays on 9.7.0.

## Build

From the project root, with `JAVA_HOME` pointing at JDK 17+:

```bat
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest
```

On Linux or macOS use `./gradlew assembleDebug testDebugUnitTest`, with `sdk.dir` in `local.properties`.

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Dashboard

The left menu is Home, Live TV, TV Guide, Radio, Search, My Items, Receivers, Settings, and Exit. Up and Down only move the highlight; OK opens that item. Home is the landing page: the programme on the last channel, Continue when there is one, then recently watched, on now, channel groups and recordings. There is no Live TV button on that page; Live TV stays in the left menu. Live TV is a three-column browser (bouquets, channels, preview); moving the highlight does not zap, OK does. TV Guide is the programme grid. Radio and Live TV each keep their own bouquet list; opening one does not zap or copy the other side's channels. Radio lists only radio bouquets (`/api/bouquets?stype=radio`). Live TV and TV Guide list only television bouquets. The open section stays marked while focus is in its panel. With a saved receiver, startup stays on Home.

Back from a page returns to the left menu. Back again, while the menu has focus, asks whether to stop EnigmaCast. Cancel stays in the app; Stop closes it and stops playback. The same question appears on the connection screen. The Exit item in the menu still closes at once.

After a box is connected, nothing is played until that box's channel list is in. A dialog blocks the screen while the list loads (no Cancel; Back does not close it). It names the step in progress — bouquets, channels, or programme information — and shows how far those steps are, as a percentage. The receiver does not report a finer figure, so the percentage moves when a step finishes. A missing programme guide is shown as no programme information; an HTTP 500 from an EPG route is an empty guide, not an error. If `/api/getservices` with `picon=1` returns HTTP 500, the same bouquet is requested again without picons. A bouquet that still fails is skipped and another bouquet is used. The dialog then closes and the stream starts.

The TV Guide page has the mini player at the top with the details of the highlighted programme next to it, and below it the guide of the current bouquet (see TV guide). Right from TV Guide enters the grid. Radio uses the live browser for radio bouquets. While a radio station plays, the mini player and the full-screen player show a now-playing card (picon, station, programme, artist when the stream or the EPG has one) instead of a black picture. The card drifts slowly so it does not burn in. OK on a programme on air starts that channel in the mini player, and OK again on the same channel opens the full-screen player. Back returns to the menu.

Search sits under Radio. Focus stays on the Search menu item until Right, which enters the field; Back returns to the menu. See [Search](#search). My Items (recordings) works the same way: the highlight stays on the menu item until Right enters the list, so opening it does not jump to the first recording.

Left on the programme on air opens the bouquet list and focuses it. Resting on a bouquet for half a second loads its channels into the guide. Right closes the bouquet list and returns to the guide. Left from the bouquet list focuses Settings. Down on the last channel or bouquet goes to the first, and Up on the first goes to the last; page and CH jumps do not wrap.

When a newer release is found at startup, the update prompt opens in its own dialog window. Focus starts on Update and cannot leave the dialog, and Back or Cancel closes it. Pressing Update stops the live stream (full screen, mini player and audio-sync preview, in both viewing modes) so the APK downloads at full speed, and shows the progress. Nothing restarts the stream during the download. Cancel or Back stops the download and resumes the stream that was playing. After a failed download, Update tries again and Cancel resumes the stream.

Bouquet rows from OpenWebif are accepted as `[reference, name]` or `[name, reference]`. The current bouquet is `info.bqref` / `info.bqname`. TV bouquets are service type 7, and playable channels are type 0. Empty bouquets stay in the list.

## Receiver

Right from Receiver enters the power controls of the box in use (see [Several receivers](#several-receivers)):

- Standby or Turn on (wake), depending on the current state
- Restart GUI (restart Enigma2 only), Restart (reboot), and Deep standby
- Sleep timer: off or 15 to 120 minutes, followed by standby or shutdown
- An info card with model, image, Enigma2 and OpenWebif versions, kernel, chipset, uptime, and tuners

Reboot, GUI restart, and deep standby ask for confirmation, with Cancel focused. Every command ends with a short message at the bottom of the screen. After standby or wake the app polls `/api/powerstate` until Enigma2 reports the new state, because the reply to `newstate` still carries the state from before the command. The power state is polled every 10 s while the dashboard is open (5 s during a restart).

In "Follow receiver" mode, when the receiver goes to standby (from the app, the remote, or its sleep timer), the live stream stops so the tuner can sleep. When it is on again the guide and mini player come back. In "Own tuner" mode standby changes nothing: the stream keeps playing, because the receiver's stream server works in standby. Deep standby stops the stream server in both modes, and cannot be undone from the app; use the remote or the power button on the receiver.

## Viewing modes

Settings → Playback → Viewing mode chooses how this device watches. Each device has its own choice.

**Own tuner (several viewers)** is the default for new installs, and installs that saved settings before this option existed switch to it too (the setting was missing there, so the default applies). Choose "Follow receiver" once to keep the old behaviour; that choice is saved.

- The device asks the stream server for `http://HOST:8001/<service reference>` itself. The receiver is not zapped and not woken. When this mode is already on at startup, or when you switch to it, the receiver is put in standby so its own screen is off (skipped if it is already in standby). Waking it later from the Receiver page is left as you left it; the stream on this TV keeps playing.
- With two tuners, two devices can each watch a different channel. A third device is told that all tuners are busy, unless its channel is on a transponder that is already tuned (Enigma2 then serves it from the same tuner). When more than one box is saved and this box has no free tuner, the next box in the list is asked for the same service reference (see [Several receivers](#several-receivers)).
- Choosing the channel that is already playing does not stop and reopen the stream. That covers the channel list, CH+/CH−, digits and search, in both viewing modes. A retry, an automatic restart, or a stream that is not actually playing still rebuilds it. OK on that channel a second time still opens the full-screen player.
- The app draws its own OSD, because the receiver's OSD exists only on its own HDMI output. OK shows the channel banner (number, name, picon, now and next with times and progress, audio and subtitle tracks, and receiver-info pills to the left of the resolution: signal strength and quality, satellite position such as 23.5°E, 19.2°E or 13.0°E, and whether the channel is free or encrypted). The pill data is separate from the skin that draws it. Signal values are shown only when they belong to this stream's tuner. Own-tuner mode puts the box in standby, and OpenWebif `/api/tunersignal` then only describes the HDMI service, which is empty. The strength and quality pills then come from that stream's frontend (`/proc/stb/frontend/<n>/snr` and `signal`, and `snr_db` when the image has it), downloaded through OpenWebif. The tuner index is the stream client in `/api/about`. Without an index, the pills are shown only when a single frontend is locked. A reading from another tuner stays hidden. Images that refuse the file download (HTTP 403 or 404) keep the pills empty. IPTV has no signal. OK again pauses or resumes with timeshift (playback continues from that moment until the next channel; a full buffer returns to live). Left opens the channel list. Info or Guide shows the programme text. Up/Down and CH+/CH− zap this device, digits pick a channel number from the current bouquet, and Down on the banner opens the quick options bar. Back closes what is open (programme dialog, options bar, programme panel, bouquet column, channel list, number entry, banner) one step at a time; with nothing open it returns to the dashboard. Colour and menu keys go nowhere; nothing is sent to the receiver.
- The full-screen channel list is dense, with about 15 rows on a 1080p screen. Each row shows the number, picon, name and the programme on air (start time, title, thin progress bar). The programmes come from one `/api/epgnow?bRef=…` request per bouquet. That request is cached and repeated after 5 minutes, or sooner once a programme has ended. Left in the list opens the bouquets with the current one focused. OK or Right shows that bouquet's channels and returns to the list, and Back closes the bouquet column. Down on the last row wraps to the first, Up on the first wraps to the last. The chosen bouquet is remembered and also shown on the dashboard. Choosing a bouquet does not zap; OK on a channel does. Right on a channel opens its programme panel (see TV guide). The bouquet column lists television bouquets, or radio bouquets when the station that is playing is radio. A programme is shown on a channel when the EPG reference differs only by service type (SD, HD, H.264, HEVC) or is the parent of a linked subservice. If the receiver has no current channel, the first television bouquet that has channels is loaded.
- Back from the full-screen player returns to the dashboard with the guide's highlight and focus on the channel that was playing, at the current time.
- The dashboard's channel list zaps this device's stream the same way. The last channel is remembered per receiver and starts again the next time that box is in use.

**Follow receiver** is the original behaviour: zapping changes the receiver's channel, the stream shows what the receiver shows, the receiver's OSD is drawn on top, and the remote controls the receiver. Use it when one TV is the only viewer and you want the receiver's menus.

Audio sync, subtitles, and HDMI passthrough work the same in both modes; they belong to the player, not to the mode.

### How the free tuner is found

Before a stream starts, the app reads:

- `/api/about`: the tuner list (`live`, `rec`, and on oe-alliance images `stream` per tuner) and the stream clients (`streams`: service, client IP, and on oe-alliance images `tuner_number`)
- `/api/statusinfo`: whether the receiver is in standby and which service it shows
- `/api/timerlist`, only when a tuner shows a recording, to learn which transponder the recording holds

A tuner is busy when it shows the receiver's own picture (only while the receiver is on), a recording, or another device's stream. Streams from this device are not counted, because the app stops its old stream before it starts the new one. Tuners that cannot receive the channel (a DVB-C tuner for a satellite channel) are left out. Every distinct transponder in use needs a tuner, so the estimate is the larger of the busy tuners and the transponders in use. A channel on a transponder that is already in use is always allowed.

The check can be wrong, so the stream start is watched as well. Enigma2 answers "200 OK" first and only then looks for a tuner; when none is free it closes the connection without sending video. A stream that ends with almost no data, or sends nothing for 10 seconds, is treated as a full receiver. With more than one saved box the app then tries the next box, the same way as when the estimate already said the tuners were full. When every saved box is full, the box you had selected stays the one in use and the player says "Alle tuners zijn bezet (2/2). Probeer het later opnieuw of kies een zender op dezelfde transponder.", with the recordings and stream clients that hold the tuners. OK tries again. A refused stream is not retried by itself. A login failure, an unreachable stream port, or a stream with no picture does not move to another box. Internet streams skip the tuner check.

Other start problems get their own message: a 401 means stream authentication is on and the credentials are missing or wrong; a refused connection means the receiver is off, in deep standby, or the stream port is wrong; data without a picture after 12 seconds usually means a scrambled channel that the CI module or card cannot descramble for this stream.

## Quick options

Down on the channel banner (own tuner), or holding Info while following the receiver, opens a row of tiles at the bottom of the player. Left/Right pick a tile, OK opens its choices, Back closes the bar.

- Search: first tile; OK opens the same search overlay as the dashboard (see Search)
- Record: records the programme on air (see Record)
- Subtitles and Audio: the stream's tracks by language, plus the stereo/HDMI output
- Picture: Auto (fit), 16:9, 4:3, Zoom (fill, crop edges), Stretch
- Audio delay: the audio sync value of the output in use, saved as in Settings
- Quality: As in Settings, Normal, or a transcoded level (720p/576p/360p) for this channel (see Stream type)
- Guide and Recent (own tuner only): back to the TV guide on the dashboard, and the last channels watched
- Sleep: closes EnigmaCast after 15–120 minutes, with a warning a minute before
- Stream info: video, audio, bitrate, buffer, dropped frames, stalls, source, route and quality
- Restart stream

## TV guide

The guide is the dashboard's TV Guide page, below the mini player: channels of the current bouquet on the left, programmes on a time line on the right in half-hour columns, two hours at a time, with a line at the current time. The channel that plays is marked. Next to the mini player are the details of the highlighted programme. It works in both viewing modes.

- Up/Down move a channel (wrap at both ends); CH+/CH− (or page keys) six channels without wrapping; Left/Right move a programme and scroll the time line, up to seven days ahead
- OK on a programme on air starts that channel in the mini player, OK again opens the full-screen player; OK on a coming programme opens a dialog with AutoSwitch, Record and Cancel (see AutoSwitch), and a small clock marks programmes with AutoSwitch set
- Left on the programme on air opens the bouquet column to switch bouquets
- Back goes to the menu; Back from the full-screen player comes back here with the highlight on the channel that was playing

Programmes are loaded per bouquet with `/api/epgmulti` in three-hour blocks, only for the visible time window, and kept for 30 minutes; rows are drawn lazily. In the channel list, Right opens a programme panel for the focused channel (`/api/epgservice`, kept 10 minutes) with the same AutoSwitch / Record dialog and clock marks.

## Stream type

Settings → Playback → Stream type (per receiver; the connection screen has the same choice under Stream quality): **Normal**, **Transcoded** or **Automatic**. The port used is logged at INFO with every stream start (`stream target route=… profile=… transcoding=… level=… url=…`, credentials removed).

- **Normal** streams the channel unchanged from the stream port (8001): full quality, the receiver's encoder is not used.
- **Transcoded** always asks the receiver's hardware encoder for a smaller stream, also at home: 720p/2.5 Mbit/s, 576p/1.2 Mbit/s or 360p/0.6 Mbit/s, as chosen in the Size row. The URL is `http://<receiver>:<transcoding port>/<service reference>?bitrate=<bit/s>&width=<w>&height=<h>`, with one question mark.
- **Automatic** streams Normal on the home address (also on a metered network) and does not ask the receiver about transcoding there. Only on the remote profile or the tunnel, and only when `/api/about` reports transcoding, does it start transcoded at 576p (or the last level that played well). Two stalls within a minute step one level down; ten minutes without a stall step one level up again. Live TV cannot show spare bandwidth, so going up is time-based, not measured.

The transcoding port (default 8002) is editable on the same card. "Ask the receiver" (also done automatically when you choose Transcoded or Automatic) reads OpenWebif's phone playlist (`/web/stream.m3u?ref=…&device=phone`) and takes the port from it: OpenWebif builds that playlist from the TranscodingSetup plugin on Broadcom boxes (8001 or 8002) and from the normal stream port 8001 on boxes with another encoder (for example HiSilicon models such as the SF8008, where Enigma2's own stream server transcodes when the URL has `bitrate`). When the playlist has no transcoding parameters, the box has no encoder and the card says so.

Transcoded viewing therefore needs a receiver with a hardware encoder, and on Broadcom boxes the TranscodingSetup plugin with its port open. When a transcoded stream fails before its first picture, the app says "Transcoded stream unavailable; check the TranscodingSetup plugin and port. Switched to normal." and plays Normal for the rest of the session on that receiver; it does not try again by itself. Changing the stream type, size or port in Settings, or picking a transcoded level in the Quality tile, tries the encoder again once.

Automatic stream restarts (after a failed start, a receiver that was away, or a quality change) are limited to three tries, 1, 2 and 4 seconds apart. After that the error stays on screen until you press OK; a stream that plays resets the count. The Quality tile in the player's quick options overrides the stream type until the next channel start, and Stream info shows the level and why it was chosen.

## Internet and IPTV streams

Bouquets can mix normal Enigma2 (DVB) channels with internet / IPTV entries. Their service reference has ten identity fields, then the stream URL (its own `:` written as `%3a`, sometimes double-encoded as `%253a`, and sometimes stored with the `%` missing as `https3a//`), then a plain `:` and the channel name, usually with type 4097, 5001, 5002 or 8193:

`4097:0:1:0:0:0:0:0:0:0:https%3a//m1b2.worldcast.tv/dancetelevisionone/2/dancetelevisionone.m3u8:Dance TV - Best of Dance TV`

EnigmaCast restores a missing `%` in `https3a//` and `http3a//`, splits the reference on its plain `:` first and decodes only the URL field, so the channel name never ends up in the address and other escapes in the URL (`%2F` in a token) stay as they are. Credentials in the URL (`user:pass@host`) are kept; Enigma2 player options (`#User-Agent=…`) are dropped. The log line is `stream target kind=internet …`.

These URLs are played directly with ExoPlayer: no tuner check, no ports 8001/8002, no Normal / Transcoded / Automatic. `.m3u8` (or `type=m3u8`) plays as HLS from the live edge, `.mpd` as DASH, `.ts` as MPEG-TS; anything else is detected by the player, and a stream it cannot read is tried once more as HLS. A failing stream is retried at most three times (1, 2 and 4 s apart); then the player stops and says "This internet stream format is not supported" or "could not be played after 3 attempts". OK tries again. In "Follow receiver" mode the app starts the URL at once and also asks the receiver to zap (best effort), so full screen, which follows the receiver, shows the same channel. The guide (`/api/epgservice`) is asked for these channels too: receivers with imported EPG for IPTV return programmes; others simply return none. Back, OSD and AutoSwitch keep working when switching between an internet channel and a DVB channel.

## AutoSwitch

In the TV guide on the dashboard and in the programme panel of the channel list, OK on a programme that has not started yet opens a dialog: **AutoSwitch**, **Record**, **Cancel**. The first button has focus.

- **AutoSwitch** remembers the programme (channel, event id, start time, title) and marks it with a small clock in both guides. OK on a marked programme offers **Cancel AutoSwitch** instead.
- Ten seconds before the programme begins, a notice appears at the top right of whatever screen is open (dashboard, full-screen player, settings): "<title> starts on <channel> in 10 s", counting down, with **OK: Switch now** and **Back: Cancel**. The notice never takes the focus: arrows and every other key keep working on the screen behind it; only OK and Back go to the notice while it shows.
- When the countdown ends, the app switches to the channel the way you watch: with an own tuner its own stream zaps (the usual tuner check applies, so a full receiver shows the normal "tuners busy" message in the player); when following the receiver, the receiver zaps. The full-screen player opens if it was not open already.
- Two programmes beginning within a minute of each other: the earliest (or the one set first) gets the notice, which names the other. Cancel shows the other one's countdown; switching drops the other, since only one channel can play.
- Reminders are saved (their own DataStore file) and survive a restart; programmes that began while the app was closed are dropped when it starts.
- AutoSwitch only works while EnigmaCast is open in the foreground. There is no alarm or background service: in the background or when the app is closed nothing appears. Back in the app within two minutes after the start, the notice still comes, with at least five seconds to cancel; later than that the reminder is dropped. It does not switch while the update dialog or the receiver setup screen is open.

## Search

One field searches live programmes, upcoming EPG, recordings, bouquet names and channel names. TMDb is reserved and unused. From two characters, after a short pause (~300 ms), results are grouped: **Now** (on air), **Upcoming** (by start time), **Recordings** (My Items, matching title or channel name), **Channels** (bouquet names and channel names). Empty query shows a hint; no hits one muted line. A spinner shows while a search is running.

Rows use the same dense style as the channel list. A programme row is `channel · title` (date and time on the second line for upcoming and recordings). A channel row is `bouquet · channel`. Choosing Now, Upcoming or a channel plays that service and switches the bouquet to the one that contains it (own tuner starts with that bouquet's list so CH+/CH− work; follow mode zaps as usual). A future programme tunes the channel that will air it. A bouquet-only row only switches the guide. A recording plays from My Items.

The same panel is on the dashboard (Search under Stream) and as the first quick-options tile in the player. On the dashboard, Right from the menu enters the field; Back returns to Search. In the player, Back closes the overlay and returns to the Search tile.

**Android TV Assistant.** "Search for Formula 1 on EnigmaCast" (or BBC One, Rai 1, yesterday's recordings) opens this panel with that query, so you need not type on the remote. "Play … on EnigmaCast" (`MEDIA_PLAY_FROM_SEARCH`) does the same and then plays the first strong hit: an exact channel name, else a live programme, else the first row. In the search field a microphone (and the remote's Search key) starts on-device speech recognition. Spoken or typed "yesterday's recordings" / "opnames van gisteren" lists recordings that started the previous local calendar day.

## Record

OK on a coming programme, or the Record tile while watching, asks where to store the capture: **this TV** or **the receiver**. Settings → Playback holds the same choice (Recording destination) and Recording size: Original, or a transcoded level on this TV (the encoder port is the one from Stream type; what you watch stays the same).

- **This TV** writes the live MPEG-TS on the device while you keep watching. Playback is from My Items as a file (not a live tuner stream), with pause, rewind and a progress bar.
- **The receiver** uses OpenWebif `/api/timeraddbyeventid` (and related timer calls). The box records independently; My Items lists it when the file is known.

My Items is the dashboard section under Search. Right from the menu enters the list. The list shows recordings and timers of the box in use (Settings → Receivers). Rows show Scheduled, Recording, Ready or Failed. OK plays a finished or in-progress item; Stop ends a capture; deleting asks for confirmation.

## Remote access

Settings → Remote access holds these per receiver (the connection screen has them too). Each receiver can have a remote profile: a DynDNS name or public address, the forwarded web, stream and transcoding ports, and HTTPS. An empty port means "same as at home". Access mode:

- **Automatic** (default): uses the home address when it answers a 1.2 s check, else the WireGuard tunnel when set up, else the remote profile. It checks again after a network change, after a failed request, and every five minutes.
- **Home only** / **Remote only**: no check.

The connection screen warns when a web address on the internet uses plain HTTP, and the player shows a one-time notice when streams go over the internet unencrypted. Enigma2 streams (8001/8002) are always plain HTTP; over the internet only a VPN encrypts them.

**WireGuard (experimental).** In Settings → Remote access, turn on "Use the built-in WireGuard tunnel", then paste the client config (`[Interface]` and `[Peer]`) or import a `.conf` file, and press Allow VPN once. The screen checks the config, shows the endpoint and AllowedIPs, and warns about a full tunnel (`0.0.0.0/0`) or a home address outside AllowedIPs. Test tunnel brings the tunnel up and asks the receiver through it. In use, the tunnel comes up only when home does not answer, carries only EnigmaCast's traffic, goes down after a network change (then home is checked again) and when EnigmaCast exits. With the switch off nothing of WireGuard runs: no VpnService, no socket binding, no route changes, and the VPN permission is not even checked. It uses the official WireGuard library (`com.wireguard.android:tunnel`, userspace backend on Android's VpnService), which adds about 3.5 MB of native code per CPU type to the APK.

The robust alternative is the official WireGuard app on the TV with the tunnel always on: leave the built-in tunnel off and use the home address (Home only).

Router and receiver:

1. WireGuard server on the router (Fritz!Box, OpenWrt, UniFi, …) with a peer for the TV; AllowedIPs on the TV side = the home subnet (for example `192.168.1.0/24`). Forward or open UDP 51820 (or the router's port).
2. Without a VPN: forward the OpenWebif HTTPS port (turn on HTTPS in OpenWebif) and 8001, and 8002 for transcoding, to the receiver; use a DynDNS name. Turn on stream authentication on the receiver. This exposes the receiver to the internet; the VPN is the safer setup.
3. For transcoding: a box with a hardware encoder; on Broadcom boxes also the TranscodingSetup plugin, whose port must be forwarded too. "Ask the receiver" in Settings → Playback → Stream type finds the port.

## Language

EnigmaCast is in English by default, also on a TV set to Dutch and after updating from an older version. Settings → Language switches between English and Nederlands. The screen redraws in the new language right away and focus stays on the language choice. On Android 13 and later the choice also shows in Android's per-app language settings, and a language chosen there is picked up by the app. Channel names, programme titles and other receiver data are shown as the receiver sends them.

All texts are Android string resources: English in `values/strings.xml`, Dutch in `values-nl/strings.xml`. Logs, the debug overlay, OSD diagnostics values and raw error texts from Android or the network stay in English.

## Several receivers

Settings → Receivers holds every saved Enigma2 box (for example living room, bedroom, vacation home). OK on a row makes that box the one in use and marks it "In use". The app does not switch to another box when the chosen one is off or does not answer. It does switch when that box has no free tuner for the channel you just chose and another saved box does: the boxes after the one in use are tried in list order, then the ones before it. The first box that can take the same service reference becomes the one in use and streams it. Tuner status, bouquets, the TV guide, the last channel, recent channels, My Items and the Receiver power page follow that box. When every box is full, the original box stays selected.

Add receiver opens the same address form as the first-launch connection screen (AutoDetect or a typed address). Save stores a new box and selects it. Edit opens that form for the box on the row. Remove asks for confirmation. Removing the box in use selects the next one in the list, or none when it was the last. Each box keeps its own login, remote profile, WireGuard config, stream type and last channel. A password or tunnel is dropped when that box is removed.

AutoDetect leaves out a host that is already in the list, so the same box cannot be saved twice. When every receiver the scan finds is already stored, the dialog says those receivers are already added.

## Settings

Right from Settings opens a submenu with Receivers, Remote access, Playback, OSD, RC Info, General, Info, and Language. Each page groups its options on cards, and every switch row toggles with OK.

- Receivers: the list of boxes. OK selects one, Edit changes its address and login, Remove deletes it. See [Several receivers](#several-receivers).
- Remote access: access mode, remote profile (DynDNS name, ports, HTTPS, Test remote) and the WireGuard tunnel (switch, paste or import the config, Allow VPN, Test tunnel)
- Playback: recording destination (this TV or the receiver) and recording size, viewing mode, stream type (Normal, Transcoded, Automatic; size and transcoding port), Stable or Fast, Stereo or HDMI, audio sync per output, DVB subtitles on or off, auto-reconnect
- OSD: channel-banner switches (signal, satellite, encryption, tuner, transponder details), transparency of the channel banner, channel list, search list, quick options and the receiver OSD (0% solid, 100% fully clear, steps of 10, default 10%), PNG or JPG, and the fast poll after a key. OSD skins (the look of the banner, channel list and notices that EnigmaCast draws itself) are stored as `osdSkin` and for now stay `STANDARD`.
- General: Navigation sounds (the click when moving with the remote; on by default)
- Info: version and build, debug overlay, verbose logging, OSD border
- Signal meter: the satellites configured on the tuner (from `/etc/enigma2/settings`, or from the television bouquets when that file cannot be read). A spinner is shown while the list loads. OK on a satellite stops this TV's stream, so the dish can move, and zaps the first television channel on that orbital position. The meter then polls `/api/tunersignal` about once a second (strength, quality, SNR in dB, BER, tuner). Back restores the channel that was on before and allows playback again; it does not restart the stream by itself.
- Oscam: the Oscam web interface on the active receiver, default port 8888 (not OpenWebif). It shows the version and uptime, each reader or proxy with its connection, current channel and ECM time, and the dvbapi users. The live log comes from `logpoll.html` (Oscam 2.26 stores each line as base64 under `oscam.lines`). Restart and Stop ask first. Stop ends the process; Oscam can then only be started on the receiver, because the web interface stops with it. A reader can be restarted on its own, and reader or user ECM statistics can be reset. The log can be paused or cleared, and the debug level can be set (off, errors, DVBAPI, ECM, or all). Login and password, when the web interface requires them, are stored with the other secrets.

**Text fields** work the same everywhere (Settings › Receivers when adding or editing a box, Remote access, Stream type, and the connection screen). Moving onto a field only highlights it: amber border and a small "Press OK to edit" hint, with no keyboard and no cursor, so Up, Down, Left and Right move straight to the next item (Right from the IP field reaches AutoDetect). OK starts editing and opens the matching keyboard: URI for host/IP, numbers for ports, password for passwords. The keyboard's Done, OK on the remote, or one Back ends editing and leaves the highlight on the same field. Back while typing never closes the screen; on Android 9–10 the first Back may only close the keyboard. The value is kept as you type and stored with Save/Connect as before. In the multi-line WireGuard field, Enter types a new line.

Stable is the default. It uses a larger live buffer (about 2–15 s) and a 4 s live offset. Fast uses the short buffer (about 0.5–5 s) and a 1 s offset. Changing playback rebuilds the player, except for the audio sync, which applies at once.

Stereo downmixes to PCM when the TV has a MediaCodec for that format. AC3, E-AC3 (including JOC), DTS and DTS-HD that the TV cannot decode are passed through HDMI when the output reports them, even while Stereo is selected. A format that can neither be decoded nor passed through is decoded to PCM by the bundled FFmpeg audio decoder (nextlib, GPL-3). Hardware decode and passthrough stay ahead of that fallback. HDMI still prefers passthrough for those formats and leaves AAC and MPEG for later. DVB subtitles are drawn on the video when the transport stream has a text track. The Playback page says whether the current stream has subtitles.

Audio sync corrects sound that runs behind or ahead of the picture, from -500 to +500 ms in 20 ms steps. Stereo and HDMI passthrough each keep their own value, because an amplifier or soundbar that decodes AC3/E-AC3 adds its own delay. The row the current track uses is marked "nu actief", and a small preview of the stream sits next to the rows. A positive value makes the picture and subtitles wait for the sound. The player's clock (the audio position) is reported that much earlier, and the audio data itself is untouched, so the correction also works for passthrough.

## Receiver setup

1. Enable OpenWebif on the receiver (OpenATV: menu, plugins, OpenWebif).
2. Note the receiver IP, the HTTP port (usually 80), and whether a username and password are set.
3. Leave the streaming server on port 8001 unless the image uses another port.
4. Start EnigmaCast. The first launch opens the connection screen.
5. Enter the receiver name, host, ports, and optional credentials (move to a field, press OK to type, then Done or Back).
6. Test connection, then Connect.

The test calls `/api/about`, probes `/grab` for PNG and JPG in `osd` and `all` modes, and checks that the streaming port accepts a TCP connection. A successful test shows the model, image, and Enigma version returned by OpenWebif.

HTTP is allowed because local OpenWebif is often plain HTTP. The connection screen warns that HTTP is not encrypted. HTTPS uses the system trust store. Trusting a self-signed certificate is off until you enable it in settings. There is no default trust-all configuration.

Passwords and WireGuard configs are encrypted with an AES-256-GCM key in the Android Keystore and kept out of the settings file; existing plain-text passwords move there on the first start. They are not written to logs, and app backup is off.

Further boxes are added under Settings → Receivers. See [Several receivers](#several-receivers).

**AutoDetect** is a button next to the IP field, on the connection screen and under Settings › Receivers (Add or Edit). It opens a dialog with a spinner ("Searching for Enigma2 receivers…") and Cancel. The search reads the TV's own IPv4 address and prefix (ConnectivityManager and the network interfaces; VPNs such as WireGuard are skipped) and scans that subnet, the whole /24 for a home network (for example 192.168.1.1–254), on ports 80 and 8080: 40 connections at a time, 400 ms connect timeout, at most 8 seconds. mDNS `_http._tcp` runs alongside. Every open port (and the address already in the form) is asked `/api/about` with short timeouts and without login; only answers that look like OpenWebif are listed (HTTP 401 counts as a receiver that needs a login). The dialog then lists each receiver as model plus IP:port (and the image), except hosts that are already saved. When OpenWebif needs no login, a small video grab (`/grab?format=jpg&mode=video`) sits on the same row so you can tell boxes apart. OK fills in host, port and HTTPS, and Save stores it. If nothing answers, the dialog says how many addresses on which subnet were searched, with Try again and Close. If the only answers belong to boxes already in the list, it says those receivers are already added. Focus returns to the AutoDetect button when the dialog closes. WireGuard is not used, and typing the IP address stays the fallback.

## OpenWebif

All paths are built in `OpenWebifEndpoints`.

- JSON API: `/api/about` (also the `transcoding` flag), `/api/epgmulti`, `/api/epgnow`, `/api/epgservice`, `/api/epgsearch`, `/api/timeraddbyeventid`, `/api/timerlist`, `/api/statusinfo`, `/api/getcurrent`, `/api/powerstate` (`newstate`: 0 toggle standby, 1 deep standby, 2 reboot, 3 restart GUI, 4 wake, 5 standby), `/api/sleeptimer`, `/api/vol`, `/api/bouquets`, `/api/bouquets?stype=radio`, `/api/getservices`, `/api/zap`, `/api/remotecontrol`
- Playlist: `/web/stream.m3u?ref=…&device=phone`, only to find the transcoding port
- OSD: `/grab?format=png|jpg&mode=osd|all|video`
- Stream: `http://HOST:8001/<percent-encoded service reference>`, with `Authorization: Basic` from the receiver's username and password when a username is set
- Own-tuner mode also reads `/api/getservices` without `sRef` (bouquet start numbers), `/api/getservices?…&picon=1` (and the same URL without `picon=1` when the receiver answers HTTP 500), picons from `/picon/`, and the stream tuner's signal from `/file?action=download&file=/proc/stb/frontend/<n>/snr` (and `signal`, `snr_db`, `lock`; `/api/file` on images that mount the file controller there)

`/api/` is JSON. The older `/web/` routes return XML and are not used. `/grab` is on the web root, not under `/api`. The stream port stays HTTP even when OpenWebif itself is HTTPS.

Remote commands use Linux input codes (`KEY_OK` = 352, `KEY_UP` = 103, `KEY_EXIT` = 174, and the rest in `EnigmaKey`). OpenWebif `remoteControl()` sends key-down and key-up for a normal command. `type=long` adds the long-press flag. There is no key-up or native repeat parameter, so holding a direction or channel key resends the short command (default every 120 ms). Info, menu, EPG, audio, and the color keys send `type=long` after the long-press threshold.

Android Back sends Enigma2 EXIT. It does not close the app. Hold Back to open Settings or exit EnigmaCast. The Android Home key stays Home. Volume keys are left to Android, because the MPEG-TS audio is decoded on the TV and the receiver volume usually does not change that stream.

## Streaming and OSD

`LivePlaybackController` keeps a single ExoPlayer for the dashboard and the full-screen player. A zap to a different channel replaces the media item. Choosing the channel that is already playing leaves the item in place. The item is marked `video/mp2t` and read as a single program, so ExoPlayer does not sniff the first bytes. The stream connection has no read timeout. A player error is shown on the mini player. Video decoders are the TV's MediaCodec decoders, with fallback enabled: MPEG-2, H.264 and H.265 work when the hardware exposes them. Audio uses MediaCodec when the device has a decoder, HDMI passthrough when it does not and the output reports the format, and FFmpeg (AC3, E-AC3, DTS and the other codecs in nextlib) when neither applies.

The OSD is the Enigma2 GUI framebuffer (commonly 720p or 1080p), not the active video rectangle. A 4:3 broadcast is letterboxed inside that framebuffer while menus still cover the full GUI. The overlay is therefore aligned to the video surface. When the OSD aspect and the surface aspect match (both 16:9), it uses fill-bounds. When they differ, it is scaled uniformly and centered so the GUI is not stretched.

Grabs are hashed with CRC32. An unchanged image does not publish a new frame. Bitmaps are decoded into a two-slot pool so the bitmap on screen is not recycled. Polling is adaptive: a fast burst after a remote key (0, 50, 100, 175, 275, 400, 600, 900 ms), then slower, then idle. A nearly transparent PNG is treated as a hidden OSD. JPEG has no alpha, so a JPEG OSD is never treated as empty.

After a remote command the service monitor polls at 50, 100, 200, 350, 500, and 1000 ms, then returns to a 1.5 s idle poll. A new service reference switches the player URL.

OSD failure does not stop video. Video failure does not stop remote commands. If the receiver disappears, a connection overlay is shown and the app probes again. When the network returns, playback and the receiver probe are nudged.

Video is drawn on a TextureView, which shows a frame as soon as the decoder hands it over. The renderer therefore releases frames at most 20 ms ahead of their display time instead of Media3's 50 ms default, so the picture does not run ahead of the sound.

## Debugging

Settings can enable:

- Debug overlay: host, service, reference, video, audio, Media3 state, buffer or live offset, OSD state, grab latency, OSD frame rate, last key
- Verbose logging, tags `EnigmaNetwork`, `EnigmaRemote`, `EnigmaOsd`, `EnigmaPlayer`, `EnigmaService`, `EnigmaConnection`
- OSD diagnostics: Grab PNG OSD, Grab JPG OSD, Grab PNG ALL, Grab JPG ALL, with status, content type, resolution, size, duration, and whether a PNG alpha channel was detected

## Known limitations

- OpenWebif cannot send a bare key-up or a kernel auto-repeat. Repeat is a series of short commands.
- PNG OSD alpha depends on the image and the `grab` binary. On some boxes `mode=osd` is opaque black outside the widgets. Use OSD diagnostics on the SF8008 to see which mode keeps transparency.
- AC3, E-AC3 and DTS passthrough need an HDMI output that reports those formats. When it does not, FFmpeg decodes them to PCM. With passthrough, Android cannot measure how long the amplifier or soundbar takes to decode, so lip sync may need the HDMI audio sync value. MPEG-2 still depends on the Android TV decoder. If the SoC has no video decoder, the picture stays black.
- Signal strength in own-tuner mode needs OpenWebif to allow downloading `/proc/stb/frontend/<n>/`. Images that block `/file` leave the strength and quality pills empty while the box is in standby. The service reference is not remapped when the next box takes a full tuner's channel, so that box must know the same reference. The signal meter uses `/api/tunersignal`, which follows the HDMI frontend, so it only measures after the app stream is stopped and the receiver is tuned to that satellite.
- Oscam is a separate web server. The app cannot start it after Stop, and it does not edit `oscam.conf`, `oscam.server` or `oscam.user`.
- DVB subtitle placement is a simple cue overlay. Bitmap cues that are not positioned as a fraction of the video fall back toward the bottom of the picture.
- AutoDetect scans only the TV's own subnet (the /24 around its address on wider networks) on ports 80 and 8080, plus mDNS. A receiver on another subnet, on another web port without mDNS, in deep standby, or behind a firewall that drops `/api/about` is not found; type the IP if needed.
- Navigation sounds off silences the click that EnigmaCast's own screens make. Android only plays it while its System sounds setting is on; the app cannot change that setting, and beeps that the TV or remote make by themselves are outside the app.
- AutoSwitch runs only while the app is open in the foreground (see AutoSwitch). A recording on this TV needs the app (and its capture service) to stay running; a recording on the receiver does not.
- Search uses OpenWebif `/api/epgsearch` for programme titles. Images without that route still match channels, bouquets and My Items. The microphone needs the TV's speech recognizer and the RECORD_AUDIO permission; Assistant SEARCH / PLAY work without it.
- Remote access, transcoding and the built-in WireGuard tunnel are built from the OpenWebif and WireGuard sources and tested only in unit tests, not on a TV and receiver. Some Android TV builds lack the VPN permission dialog; the app then says so and the WireGuard app is the way to go.
- Over the remote profile the receiver sees the router's address for this device's stream, so telling this device's own stream apart from others may miss it and count one tuner too many.
- Own-tuner mode estimates free tuners from what OpenWebif reports. OpenPLi-based images (E2OpenPlugins OpenWebif) do not say which tuner a stream uses and may list a stream as a recording; FBC tuners, unicable, and tuners shared through a loop-through count as separate tuners there even when the dish cabling allows fewer. When the estimate is wrong, the stream start check still catches a refused stream.
- A CI module or smartcard usually descrambles a limited number of channels at once, often one or two. Enigma2 still hands out the tuner, so a scrambled channel can start without a picture. The app says so after 12 seconds; it cannot see the CAM's limit in advance.
- A recording that starts while every tuner streams can end a stream; whether Enigma2 takes the tuner from a stream depends on the image. The device whose stream ends sees the "tuners busy" message.
- Channel numbers come from the bouquet start positions in `/api/getservices`; images without `startpos` number each bouquet from 1. When an internet bouquet repeats one position for several streams, the app keeps counting. Number entry looks only in the bouquet that is open.
- A future receiver-side plugin can replace the OpenWebif sources (`OsdSource`, `RemoteControlSource`, `ServiceStateSource`) with a push transport without rewriting the player screen.

## Troubleshooting

- Test connection fails immediately: check the IP, that OpenWebif is enabled, and that the TV is on the same LAN. A VPN app on the TV can hide the receiver unless it routes the home subnet; the built-in tunnel only comes up when home does not answer.
- HTTP 401: fill in the OpenWebif username and password.
- AutoDetect finds nothing: `adb logcat -s EnigmaDiscovery` shows the interfaces the TV reported (`interface eth0 192.168.1.42/24 via …`), the subnet that was scanned, every open port (`open 192.168.1.209:80`), each `/api/about` answer (`about 192.168.1.209:80 -> HTTP 200, Enigma2 …`) and the timing. "no private IPv4 LAN found" means the TV reported no usable address; a receiver missing from the `open` lines was not reachable on 80/8080 from the TV.
- An IPTV / internet channel tries the receiver and fails: the service reference should contain `http%3a//` or `https%3a//` after the tenth field. `adb logcat | grep "stream target"` should say `kind=internet`; if it says `kind=enigma2`, the bouquet entry has no embedded URL.
- An internet channel says the format is not supported: `adb logcat | grep -E "switching live stream|internet stream"` shows `type=` (HLS, DASH, TS or auto) and each try. The stream may need a header the app does not send, or use a format the TV's player cannot read; check the URL in VLC on a PC.
- The remote clicks on every move: Settings › General › Navigation sounds off. If it still clicks, turn off System sounds in the Android TV settings (Device preferences › Sound) or check the TV's own key-beep option.
- The stream does not start: `adb logcat | grep -E "stream target|switching live stream"` shows the exact URL (without credentials), the route and whether it is transcoded. At home it should be `http://<receiver>:8001/<service reference>`; a transcoded URL on a closed port means the transcoding port in the receiver settings does not match the TranscodingSetup plugin (use "Ask the receiver" in Settings → Playback → Stream type), or choose Normal.
- HTTPS fails with a certificate error: enable the self-signed option only for that receiver, or install a certificate the TV already trusts.
- "All tuners are busy" while you expect a free tuner: put the receiver in standby (not deep standby) so its own picture frees a tuner, and check for running recordings or other devices streaming (OpenWebif's About page lists them). Choosing a channel on the same transponder as a running stream always works.
- "The receiver asks for a password for streams": Enigma2's "Require authentication for http streams" is on (OpenATV: Menu → Setup → Usage & GUI → Customize System Settings). The stream server checks the receiver's Linux account (usually `root` and its password), not a separate OpenWebif password. Enter that user and password under Settings → Receivers → Edit for that box, or turn the setting off. OpenWebif's "Enable authentication for streaming" only affects its playlists, not this check.
- Own-tuner mode shows no picture on a scrambled channel: the CI module or card is busy with another channel or stream. Pick a free-to-air channel or stop the other stream.
- Picture without OSD: open OSD diagnostics and try PNG OSD, then JPG OSD. Confirm `grab` works in a browser on the same URL.
- OSD without picture: confirm port 8001 is open and the receiver is not in standby. The player reconnects on its own when auto-reconnect is on.
- Zapping on the receiver updates the TV a moment later: that delay is the service poll plus tuner and decoder startup. Sending the zap from the Android remote triggers the fast poll.
- Sound behind the picture: open Settings → Playback → Audio sync and raise the value of the row marked "active now" until lips and voice match. Usually 60–200 ms with passthrough to an amplifier or soundbar. Check the TV or amplifier for its own lip-sync or "AV sync" setting first, so the two do not add up.
- Menus feel sluggish: turn on the debug overlay and read OSD latency and frame rate. The receiver's `grab` cost is usually the limit, not Compose.
