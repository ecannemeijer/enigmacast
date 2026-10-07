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

The full-screen player still shows the receiver's own OSD. The dashboard adds a local channel list and EPG so you can browse and zap without opening that player.

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

The left menu is Stream, Receiver, RC Info, OSD skins, Settings, and Exit. The open section stays marked while focus is in its panel, and a pill under the logo shows the receiver power state (On, Standby, Not reachable, Restarting…). With a saved receiver, startup stays on the dashboard and the current service starts in the mini player.

The Stream page has the mini player at the top with the details of the highlighted programme next to it, and below it the TV guide of the current bouquet (see TV guide). Right from Stream enters the guide. OK on a programme on air starts that channel in the mini player, and OK again on the same channel opens the full-screen player. Back returns to the menu.

Left on the programme on air opens the bouquet list and focuses it. Resting on a bouquet for half a second loads its channels into the guide. Right closes the bouquet list and returns to the guide. Left from the bouquet list focuses Settings.

OSD skins will let you choose the look of the OSD that EnigmaCast draws itself (channel banner, channel list, notices). For now it only shows "Coming soon"; the choice will be saved as `osdSkin` in the settings (currently always `STANDARD`).

When a newer release is found at startup, the update prompt opens in its own dialog window. Focus starts on Update and cannot leave the dialog, and Back or Cancel closes it. Pressing Update stops the live stream (full screen, mini player and audio-sync preview, in both viewing modes) so the APK downloads at full speed, and shows the progress. Nothing restarts the stream during the download. Cancel or Back stops the download and resumes the stream that was playing. After a failed download, Update tries again and Cancel resumes the stream.

Bouquet rows from OpenWebif are accepted as `[reference, name]` or `[name, reference]`. The current bouquet is `info.bqref` / `info.bqname`. TV bouquets are service type 7, and playable channels are type 0. Empty bouquets stay in the list.

## Receiver

Right from Receiver enters the power controls:

- Standby or Turn on (wake), depending on the current state
- Restart GUI (restart Enigma2 only), Restart (reboot), and Deep standby
- Sleep timer: off or 15 to 120 minutes, followed by standby or shutdown
- An info card with model, image, Enigma2 and OpenWebif versions, kernel, chipset, uptime, and tuners

Reboot, GUI restart, and deep standby ask for confirmation, with Cancel focused. Every command ends with a short message at the bottom of the screen. After standby or wake the app polls `/api/powerstate` until Enigma2 reports the new state, because the reply to `newstate` still carries the state from before the command. The power state is polled every 10 s while the dashboard is open (5 s during a restart).

In "Follow receiver" mode, when the receiver goes to standby (from the app, the remote, or its sleep timer), the live stream stops so the tuner can sleep. When it is on again the guide and mini player come back. In "Own tuner" mode standby changes nothing: the stream keeps playing, because the receiver's stream server works in standby. Deep standby stops the stream server in both modes, and cannot be undone from the app; use the remote or the power button on the receiver.

## Viewing modes

Settings → Playback → Viewing mode chooses how this device watches. Each device has its own choice.

**Own tuner (several viewers)** is the default for new installs, and installs that saved settings before this option existed switch to it too (the setting was missing there, so the default applies). Choose "Follow receiver" once to keep the old behaviour; that choice is saved.

- The device asks the stream server for `http://HOST:8001/<service reference>` itself. The receiver is not zapped, not woken, and may stay in standby. Its own screen, if it is on, is left alone.
- With two tuners, two devices can each watch a different channel. A third device is told that all tuners are busy, unless its channel is on a transponder that is already tuned (Enigma2 then serves it from the same tuner).
- The app draws its own OSD, because the receiver's OSD exists only on its own HDMI output. OK shows the channel banner (number, name, picon, now and next with times and progress, audio and subtitle tracks). OK again opens the channel list. Info or Guide shows the programme text. Up/Down and CH+/CH− zap this device, digits pick a channel number from the current bouquet, and Down on the banner opens the quick options bar. Back closes what is open (record question, options bar, programme panel, bouquet column, channel list, number entry, banner) one step at a time; with nothing open it returns to the dashboard. Colour and menu keys go nowhere; nothing is sent to the receiver.
- The full-screen channel list is dense, with about 15 rows on a 1080p screen. Each row shows the number, picon, name and the programme on air (start time, title, thin progress bar). The programmes come from one `/api/epgnow?bRef=…` request per bouquet. That request is cached and repeated after 5 minutes, or sooner once a programme has ended. Left in the list opens the bouquets with the current one focused. OK or Right shows that bouquet's channels and returns to the list, and Back closes the bouquet column. The chosen bouquet is remembered and also shown on the dashboard. Choosing a bouquet does not zap; OK on a channel does. Right on a channel opens its programme panel (see TV guide).
- Back from the full-screen player returns to the dashboard with the guide's highlight and focus on the channel that was playing, at the current time.
- The dashboard's channel list zaps this device's stream the same way. Its last channel is remembered and starts again the next time.

**Follow receiver** is the original behaviour: zapping changes the receiver's channel, the stream shows what the receiver shows, the receiver's OSD is drawn on top, and the remote controls the receiver. Use it when one TV is the only viewer and you want the receiver's menus.

Audio sync, subtitles, and HDMI passthrough work the same in both modes; they belong to the player, not to the mode.

### How the free tuner is found

Before a stream starts, the app reads:

- `/api/about`: the tuner list (`live`, `rec`, and on oe-alliance images `stream` per tuner) and the stream clients (`streams`: service, client IP, and on oe-alliance images `tuner_number`)
- `/api/statusinfo`: whether the receiver is in standby and which service it shows
- `/api/timerlist`, only when a tuner shows a recording, to learn which transponder the recording holds

A tuner is busy when it shows the receiver's own picture (only while the receiver is on), a recording, or another device's stream. Streams from this device are not counted, because the app stops its old stream before it starts the new one. Tuners that cannot receive the channel (a DVB-C tuner for a satellite channel) are left out. Every distinct transponder in use needs a tuner, so the estimate is the larger of the busy tuners and the transponders in use. A channel on a transponder that is already in use is always allowed.

The check can be wrong, so the stream start is watched as well. Enigma2 answers "200 OK" first and only then looks for a tuner; when none is free it closes the connection without sending video. A stream that ends with almost no data, or sends nothing for 10 seconds, is reported the same way as a full receiver: "Alle tuners zijn bezet (2/2). Probeer het later opnieuw of kies een zender op dezelfde transponder.", with the recordings and stream clients that hold the tuners. OK tries again. A refused stream is not retried by itself.

Other start problems get their own message: a 401 means stream authentication is on and the credentials are missing or wrong; a refused connection means the receiver is off, in deep standby, or the stream port is wrong; data without a picture after 12 seconds usually means a scrambled channel that the CI module or card cannot descramble for this stream.

## Quick options

Down on the channel banner (own tuner), or holding Info while following the receiver, opens a row of tiles at the bottom of the player. Left/Right pick a tile, OK opens its choices, Back closes the bar.

- Subtitles and Audio: the stream's tracks by language, plus the stereo/HDMI output
- Picture: Auto (fit), 16:9, 4:3, Zoom (fill, crop edges), Stretch
- Audio delay: the audio sync value of the output in use, saved as in Settings
- Quality: Automatic or a fixed level (see Stream quality)
- Guide and Recent (own tuner only): back to the TV guide on the dashboard, and the last channels watched
- Sleep: closes EnigmaCast after 15–120 minutes, with a warning a minute before
- Stream info: video, audio, bitrate, buffer, dropped frames, stalls, source, route and quality
- Restart stream

## TV guide

The guide is the dashboard's Stream page, below the mini player: channels of the current bouquet on the left, programmes on a time line on the right in half-hour columns, two hours at a time, with a line at the current time. The channel that plays is marked. Next to the mini player are the details of the highlighted programme. It works in both viewing modes.

- Up/Down move a channel, CH+/CH− (or page keys) six channels; Left/Right move a programme and scroll the time line, up to seven days ahead
- OK on a programme on air starts that channel in the mini player, OK again opens the full-screen player; OK on a coming programme asks whether to record it (`/api/timeraddbyeventid`), and a red dot marks it afterwards
- Left on the programme on air opens the bouquet column to switch bouquets
- Back goes to the menu; Back from the full-screen player comes back here with the highlight on the channel that was playing

Programmes are loaded per bouquet with `/api/epgmulti` in three-hour blocks, only for the visible time window, and kept for 30 minutes; rows are drawn lazily. In the channel list, Right opens a programme panel for the focused channel (`/api/epgservice`, kept 10 minutes) with the same OK-to-record.

## Stream quality

Per receiver (connection screen → Stream quality): Original, Transcoded or Automatic. The port used is logged at INFO with every stream start (`stream target route=… profile=… transcoding=… level=… url=…`, credentials removed).

- **Original** streams port 8001 unchanged.
- **Transcoded** asks the receiver's hardware encoder for 720p/2.5 Mbit/s, 576p/1.2 Mbit/s or 360p/0.6 Mbit/s, on the transcoding port (usually 8002 on Broadcom boxes) with `bitrate`, `width` and `height` parameters as OpenWebif's own playlists do.
- **Automatic** always streams the original on the home address (also on a metered network) and does not ask the receiver about transcoding there. Only on the remote profile or the tunnel does it start transcoded at 576p (or the last level that played well). Two stalls within a minute step one level down; ten minutes without a stall step one level up again. Live TV cannot show spare bandwidth, so going up is time-based, not measured.

Transcoding is only offered when `/api/about` reports it (`transcoding: true`, needs an encoder and the TranscodingSetup plugin). Without it every profile plays the original. A transcoded stream that fails before its first picture falls back to the original once, for the rest of the session. The transcoding port depends on the TranscodingSetup plugin (8001 or 8002), so check it there if transcoded streams fail.

Automatic stream restarts (after a failed start, a receiver that was away, or a quality change) are limited to three tries, 1, 2 and 4 seconds apart. After that the error stays on screen until you press OK; a stream that plays resets the count. The Quality tile overrides the level until the next channel start, and Stream info shows the level and why it was chosen.

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
3. For transcoding: a box with a hardware encoder and the TranscodingSetup plugin.

## Language

EnigmaCast is in English by default, also on a TV set to Dutch and after updating from an older version. Settings → Language switches between English and Nederlands. The screen redraws in the new language right away and focus stays on the language choice. On Android 13 and later the choice also shows in Android's per-app language settings, and a language chosen there is picked up by the app. Channel names, programme titles and other receiver data are shown as the receiver sends them.

All texts are Android string resources: English in `values/strings.xml`, Dutch in `values-nl/strings.xml`. Logs, the debug overlay, OSD diagnostics values and raw error texts from Android or the network stay in English.

## Settings

Right from Settings opens a submenu with Receiver, Remote access, Playback, OSD, Info, and Language. Each page groups its options on cards, and every switch row toggles with OK.

- Receiver: host, ports, credentials, and connection test
- Remote access: access mode, remote profile (DynDNS name, ports, HTTPS, Test remote) and the WireGuard tunnel (switch, paste or import the config, Allow VPN, Test tunnel)
- Playback: Stable or Fast, Stereo or HDMI, audio sync per output, DVB subtitles on or off, auto-reconnect
- OSD: PNG or JPG, and the fast poll after a key
- Info: version and build, debug overlay, verbose logging, OSD border

Stable is the default. It uses a larger live buffer (about 2–15 s) and a 4 s live offset. Fast uses the short buffer (about 0.5–5 s) and a 1 s offset. Changing playback rebuilds the player, except for the audio sync, which applies at once.

Stereo downmixes AC3 and E-AC3 to PCM. HDMI leaves passthrough to the device when the HDMI output reports those formats. DVB subtitles are drawn on the video when the transport stream has a text track. The Playback page says whether the current stream has subtitles.

Audio sync corrects sound that runs behind or ahead of the picture, from -500 to +500 ms in 20 ms steps. Stereo and HDMI passthrough each keep their own value, because an amplifier or soundbar that decodes AC3/E-AC3 adds its own delay. The row the current track uses is marked "nu actief", and a small preview of the stream sits next to the rows. A positive value makes the picture and subtitles wait for the sound. The player's clock (the audio position) is reported that much earlier, and the audio data itself is untouched, so the correction also works for passthrough.

## Receiver setup

1. Enable OpenWebif on the receiver (OpenATV: menu, plugins, OpenWebif).
2. Note the receiver IP, the HTTP port (usually 80), and whether a username and password are set.
3. Leave the streaming server on port 8001 unless the image uses another port.
4. Start EnigmaCast. The first launch opens the connection screen.
5. Enter the receiver name, host, ports, and optional credentials.
6. Test connection, then Connect.

The test calls `/api/about`, probes `/grab` for PNG and JPG in `osd` and `all` modes, and checks that the streaming port accepts a TCP connection. A successful test shows the model, image, and Enigma version returned by OpenWebif.

HTTP is allowed because local OpenWebif is often plain HTTP. The connection screen warns that HTTP is not encrypted. HTTPS uses the system trust store. Trusting a self-signed certificate is off until you enable it in settings. There is no default trust-all configuration.

Passwords and WireGuard configs are encrypted with an AES-256-GCM key in the Android Keystore and kept out of the settings file; existing plain-text passwords move there on the first start. They are not written to logs, and app backup is off.

The settings model is a list of receivers plus an active id, so more than one receiver can be stored later. Version 1 edits the active receiver.

Network discovery is a Search button. It browses mDNS `_http._tcp` and fills in a host if something answers. Many images do not advertise OpenWebif reliably, so discovery never runs by itself and a failure leaves manual setup working.

## OpenWebif

All paths are built in `OpenWebifEndpoints`.

- JSON API: `/api/about` (also the `transcoding` flag), `/api/epgmulti`, `/api/timeraddbyeventid`, `/api/statusinfo`, `/api/getcurrent`, `/api/powerstate` (`newstate`: 0 toggle standby, 1 deep standby, 2 reboot, 3 restart GUI, 4 wake, 5 standby), `/api/sleeptimer`, `/api/vol`, `/api/bouquets`, `/api/getservices`, `/api/epgservice`, `/api/zap`, `/api/remotecontrol`
- OSD: `/grab?format=png|jpg&mode=osd|all|video`
- Stream: `http://HOST:8001/<percent-encoded service reference>`, with `Authorization: Basic` from the receiver's username and password when a username is set
- Own-tuner mode also reads `/api/timerlist`, `/api/getservices` without `sRef` (bouquet start numbers), `/api/getservices?…&picon=1`, and picons from `/picon/`

`/api/` is JSON. The older `/web/` routes return XML and are not used. `/grab` is on the web root, not under `/api`. The stream port stays HTTP even when OpenWebif itself is HTTPS.

Remote commands use Linux input codes (`KEY_OK` = 352, `KEY_UP` = 103, `KEY_EXIT` = 174, and the rest in `EnigmaKey`). OpenWebif `remoteControl()` sends key-down and key-up for a normal command. `type=long` adds the long-press flag. There is no key-up or native repeat parameter, so holding a direction or channel key resends the short command (default every 120 ms). Info, menu, EPG, audio, and the color keys send `type=long` after the long-press threshold.

Android Back sends Enigma2 EXIT. It does not close the app. Hold Back to open Settings or exit EnigmaCast. The Android Home key stays Home. Volume keys are left to Android, because the MPEG-TS audio is decoded on the TV and the receiver volume usually does not change that stream.

## Streaming and OSD

`LivePlaybackController` keeps a single ExoPlayer for the dashboard and the full-screen player. A zap replaces the media item. The item is marked `video/mp2t` and read as a single program, so ExoPlayer does not sniff the first bytes. The stream connection has no read timeout. A player error is shown on the mini player. Decoders are the TV's MediaCodec decoders, with fallback enabled: MPEG-2, H.264, H.265, AAC, AC3, and E-AC3 work when the hardware exposes them. There is no bundled FFmpeg decoder.

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
- AC3 and E-AC3 passthrough need an HDMI output that reports those formats. Stereo mode always downmixes. With passthrough, Android cannot measure how long the amplifier or soundbar takes to decode, so lip sync may need the HDMI audio sync value. MPEG-2 still depends on the Android TV decoder. If the SoC has no decoder, that track stays silent or the video stays black.
- DVB subtitle placement is a simple cue overlay. Bitmap cues that are not positioned as a fraction of the video fall back toward the bottom of the picture.
- mDNS discovery is best-effort.
- Remote access, transcoding and the built-in WireGuard tunnel are built from the OpenWebif and WireGuard sources and tested only in unit tests, not on a TV and receiver. Some Android TV builds lack the VPN permission dialog; the app then says so and the WireGuard app is the way to go.
- Over the remote profile the receiver sees the router's address for this device's stream, so telling this device's own stream apart from others may miss it and count one tuner too many.
- Own-tuner mode estimates free tuners from what OpenWebif reports. OpenPLi-based images (E2OpenPlugins OpenWebif) do not say which tuner a stream uses and may list a stream as a recording; FBC tuners, unicable, and tuners shared through a loop-through count as separate tuners there even when the dish cabling allows fewer. When the estimate is wrong, the stream start check still catches a refused stream.
- A CI module or smartcard usually descrambles a limited number of channels at once, often one or two. Enigma2 still hands out the tuner, so a scrambled channel can start without a picture. The app says so after 12 seconds; it cannot see the CAM's limit in advance.
- A recording that starts while every tuner streams can end a stream; whether Enigma2 takes the tuner from a stream depends on the image. The device whose stream ends sees the "tuners busy" message.
- Channel numbers come from the bouquet start positions in `/api/getservices`; images without `startpos` number each bouquet from 1. Number entry looks only in the bouquet that is open.
- A future receiver-side plugin can replace the OpenWebif sources (`OsdSource`, `RemoteControlSource`, `ServiceStateSource`) with a push transport without rewriting the player screen.

## Troubleshooting

- Test connection fails immediately: check the IP, that OpenWebif is enabled, and that the TV is on the same LAN. A VPN app on the TV can hide the receiver unless it routes the home subnet; the built-in tunnel only comes up when home does not answer.
- HTTP 401: fill in the OpenWebif username and password.
- The stream does not start: `adb logcat | grep -E "stream target|switching live stream"` shows the exact URL (without credentials), the route and whether it is transcoded. At home it should be `http://<receiver>:8001/<service reference>`; a transcoded URL on a closed port means the transcoding port in the receiver settings does not match the TranscodingSetup plugin, or choose Original.
- HTTPS fails with a certificate error: enable the self-signed option only for that receiver, or install a certificate the TV already trusts.
- "All tuners are busy" while you expect a free tuner: put the receiver in standby (not deep standby) so its own picture frees a tuner, and check for running recordings or other devices streaming (OpenWebif's About page lists them). Choosing a channel on the same transponder as a running stream always works.
- "The receiver asks for a password for streams": Enigma2's "Require authentication for http streams" is on (OpenATV: Menu → Setup → Usage & GUI → Customize System Settings). The stream server checks the receiver's Linux account (usually `root` and its password), not a separate OpenWebif password. Enter that user and password under Settings → Receiver, or turn the setting off. OpenWebif's "Enable authentication for streaming" only affects its playlists, not this check.
- Own-tuner mode shows no picture on a scrambled channel: the CI module or card is busy with another channel or stream. Pick a free-to-air channel or stop the other stream.
- Picture without OSD: open OSD diagnostics and try PNG OSD, then JPG OSD. Confirm `grab` works in a browser on the same URL.
- OSD without picture: confirm port 8001 is open and the receiver is not in standby. The player reconnects on its own when auto-reconnect is on.
- Zapping on the receiver updates the TV a moment later: that delay is the service poll plus tuner and decoder startup. Sending the zap from the Android remote triggers the fast poll.
- Sound behind the picture: open Settings → Playback → Audio sync and raise the value of the row marked "active now" until lips and voice match. Usually 60–200 ms with passthrough to an amplifier or soundbar. Check the TV or amplifier for its own lip-sync or "AV sync" setting first, so the two do not add up.
- Menus feel sluggish: turn on the debug overlay and read OSD latency and frame rate. The receiver's `grab` cost is usually the limit, not Compose.
