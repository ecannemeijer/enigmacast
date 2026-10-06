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

That is the "Ontvanger volgen" mode. The default mode is "Eigen tuner": each Android TV streams its own channel straight from the receiver without zapping it, so several TVs can watch different channels at once, one per free tuner. See [Viewing modes](#viewing-modes).

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

The left menu is Stream, Receiver, RC Info, Settings, and Exit. The open section stays marked while focus is in its panel, and a pill under the logo shows the receiver power state (Aan, Stand-by, Niet bereikbaar, Herstart…). With a saved receiver, startup stays on the dashboard and the current service starts in the mini player.

Right from Stream enters the channel list of the current TV bouquet. Moving the focus previews that channel's EPG on the right and in the header. OK zaps and starts the mini player. OK again on the same channel opens the full-screen player. Right from a channel opens the EPG rows.

Left from the channel list opens the bouquet list and focuses it. Resting on a bouquet for half a second loads its playable channels to the right. Right closes the bouquet list and returns to the channels. Left from the bouquet list focuses Settings.

Bouquet rows from OpenWebif are accepted as `[reference, name]` or `[name, reference]`. The current bouquet is `info.bqref` / `info.bqname`. TV bouquets are service type 7, and playable channels are type 0. Empty bouquets stay in the list.

## Receiver

Right from Receiver enters the power controls:

- Stand-by or Aanzetten (wake), depending on the current state
- GUI herstarten (restart Enigma2 only), Herstarten (reboot), and Diep slapen (deep standby)
- Slaaptimer: off or 15 to 120 minutes, followed by standby or shutdown
- An info card with model, image, Enigma2 and OpenWebif versions, kernel, chipset, uptime, and tuners

Reboot, GUI restart, and deep standby ask for confirmation, with Cancel focused. Every command ends with a short message at the bottom of the screen. After standby or wake the app polls `/api/powerstate` until Enigma2 reports the new state, because the reply to `newstate` still carries the state from before the command. The power state is polled every 10 s while the dashboard is open (5 s during a restart).

In "Ontvanger volgen" mode, when the receiver goes to standby (from the app, the remote, or its sleep timer), the live stream stops so the tuner can sleep. When it is on again the guide and mini player come back. In "Eigen tuner" mode standby changes nothing: the stream keeps playing, because the receiver's stream server works in standby. Deep standby stops the stream server in both modes, and cannot be undone from the app; use the remote or the power button on the receiver.

## Viewing modes

Settings → Weergave → Kijkmodus chooses how this device watches. Each device has its own choice.

**Eigen tuner (meerdere kijkers)** is the default for new installs, and installs that saved settings before this option existed switch to it too (the setting was missing there, so the default applies). Choose "Ontvanger volgen" once to keep the old behaviour; that choice is saved.

- The device asks the stream server for `http://HOST:8001/<service reference>` itself. The receiver is not zapped, not woken, and may stay in standby. Its own screen, if it is on, is left alone.
- With two tuners, two devices can each watch a different channel. A third device is told that all tuners are busy, unless its channel is on a transponder that is already tuned (Enigma2 then serves it from the same tuner).
- The app draws its own OSD, because the receiver's OSD exists only on its own HDMI output. OK shows the channel banner (number, name, picon, now and next with times and progress, audio and subtitle tracks). OK again opens the channel list. Info or Guide shows the programme text. Up/Down and CH+/CH− zap this device, digits pick a channel number from the current bouquet, and Back closes the banner or list and then returns to the dashboard. Colour and menu keys go nowhere; nothing is sent to the receiver.
- The dashboard's channel list zaps this device's stream the same way. Its last channel is remembered and starts again the next time.

**Ontvanger volgen (huidige modus)** is the original behaviour: zapping changes the receiver's channel, the stream shows what the receiver shows, the receiver's OSD is drawn on top, and the remote controls the receiver. Use it when one TV is the only viewer and you want the receiver's menus.

Audio sync, subtitles, and HDMI passthrough work the same in both modes; they belong to the player, not to the mode.

### How the free tuner is found

Before a stream starts, the app reads:

- `/api/about`: the tuner list (`live`, `rec`, and on oe-alliance images `stream` per tuner) and the stream clients (`streams`: service, client IP, and on oe-alliance images `tuner_number`)
- `/api/statusinfo`: whether the receiver is in standby and which service it shows
- `/api/timerlist`, only when a tuner shows a recording, to learn which transponder the recording holds

A tuner is busy when it shows the receiver's own picture (only while the receiver is on), a recording, or another device's stream. Streams from this device are not counted, because the app stops its old stream before it starts the new one. Tuners that cannot receive the channel (a DVB-C tuner for a satellite channel) are left out. Every distinct transponder in use needs a tuner, so the estimate is the larger of the busy tuners and the transponders in use. A channel on a transponder that is already in use is always allowed.

The check can be wrong, so the stream start is watched as well. Enigma2 answers "200 OK" first and only then looks for a tuner; when none is free it closes the connection without sending video. A stream that ends with almost no data, or sends nothing for 10 seconds, is reported the same way as a full receiver: "Alle tuners zijn bezet (2/2). Probeer het later opnieuw of kies een zender op dezelfde transponder.", with the recordings and stream clients that hold the tuners. OK tries again. A refused stream is not retried by itself.

Other start problems get their own message: a 401 means stream authentication is on and the credentials are missing or wrong; a refused connection means the receiver is off, in deep standby, or the stream port is wrong; data without a picture after 12 seconds usually means a scrambled channel that the CI module or card cannot descramble for this stream.

## Settings

Right from Settings opens a submenu with Receiver, Playback, OSD, and Info. Each page groups its options on cards, and every switch row toggles with OK.

- Receiver: host, ports, credentials, and connection test
- Playback: Stable or Fast, Stereo or HDMI, audio sync per output, DVB subtitles on or off, auto-reconnect
- OSD: PNG or JPG, and the fast poll after a key
- Info: version and build, debug overlay, verbose logging, OSD border

Stable is the default. It uses a larger live buffer (about 2–15 s) and a 4 s live offset. Fast uses the short buffer (about 0.5–5 s) and a 1 s offset. Changing playback rebuilds the player, except for the audio sync, which applies at once.

Stereo downmixes AC3 and E-AC3 to PCM. HDMI leaves passthrough to the device when the HDMI output reports those formats. DVB subtitles are drawn on the video when the transport stream has a text track. The Playback page says whether the current stream has subtitles.

Audio sync (Geluid synchroniseren) corrects sound that runs behind or ahead of the picture, from -500 to +500 ms in 20 ms steps. Stereo and HDMI passthrough each keep their own value, because an amplifier or soundbar that decodes AC3/E-AC3 adds its own delay. The row the current track uses is marked "nu actief", and a small preview of the stream sits next to the rows. A positive value makes the picture and subtitles wait for the sound. The player's clock (the audio position) is reported that much earlier, and the audio data itself is untouched, so the correction also works for passthrough.

## Receiver setup

1. Enable OpenWebif on the receiver (OpenATV: menu, plugins, OpenWebif).
2. Note the receiver IP, the HTTP port (usually 80), and whether a username and password are set.
3. Leave the streaming server on port 8001 unless the image uses another port.
4. Start EnigmaCast. The first launch opens the connection screen.
5. Enter the receiver name, host, ports, and optional credentials.
6. Test connection, then Connect.

The test calls `/api/about`, probes `/grab` for PNG and JPG in `osd` and `all` modes, and checks that the streaming port accepts a TCP connection. A successful test shows the model, image, and Enigma version returned by OpenWebif.

HTTP is allowed because local OpenWebif is often plain HTTP. The connection screen warns that HTTP is not encrypted. HTTPS uses the system trust store. Trusting a self-signed certificate is off until you enable it in settings. There is no default trust-all configuration.

Passwords are stored in app-private Preferences DataStore. They are not written to logs. Encrypted DataStore (`datastore-tink`) is still alpha, so it is not used.

The settings model is a list of receivers plus an active id, so more than one receiver can be stored later. Version 1 edits the active receiver.

Network discovery is a Search button. It browses mDNS `_http._tcp` and fills in a host if something answers. Many images do not advertise OpenWebif reliably, so discovery never runs by itself and a failure leaves manual setup working.

## OpenWebif

All paths are built in `OpenWebifEndpoints`.

- JSON API: `/api/about`, `/api/statusinfo`, `/api/getcurrent`, `/api/powerstate` (`newstate`: 0 toggle standby, 1 deep standby, 2 reboot, 3 restart GUI, 4 wake, 5 standby), `/api/sleeptimer`, `/api/vol`, `/api/bouquets`, `/api/getservices`, `/api/epgservice`, `/api/zap`, `/api/remotecontrol`
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
- Passwords are not encrypted at rest.
- Own-tuner mode estimates free tuners from what OpenWebif reports. OpenPLi-based images (E2OpenPlugins OpenWebif) do not say which tuner a stream uses and may list a stream as a recording; FBC tuners, unicable, and tuners shared through a loop-through count as separate tuners there even when the dish cabling allows fewer. When the estimate is wrong, the stream start check still catches a refused stream.
- A CI module or smartcard usually descrambles a limited number of channels at once, often one or two. Enigma2 still hands out the tuner, so a scrambled channel can start without a picture. The app says so after 12 seconds; it cannot see the CAM's limit in advance.
- A recording that starts while every tuner streams can end a stream; whether Enigma2 takes the tuner from a stream depends on the image. The device whose stream ends sees the "tuners busy" message.
- Channel numbers come from the bouquet start positions in `/api/getservices`; images without `startpos` number each bouquet from 1. Number entry looks only in the bouquet that is open.
- A future receiver-side plugin can replace the OpenWebif sources (`OsdSource`, `RemoteControlSource`, `ServiceStateSource`) with a push transport without rewriting the player screen.

## Troubleshooting

- Test connection fails immediately: check the IP, that OpenWebif is enabled, and that the TV is on the same LAN. A VPN on the TV will hide the receiver.
- HTTP 401: fill in the OpenWebif username and password.
- HTTPS fails with a certificate error: enable the self-signed option only for that receiver, or install a certificate the TV already trusts.
- "Alle tuners zijn bezet" while you expect a free tuner: put the receiver in standby (not deep standby) so its own picture frees a tuner, and check for running recordings or other devices streaming (OpenWebif's About page lists them). Choosing a channel on the same transponder as a running stream always works.
- "De ontvanger vraagt een wachtwoord voor streams": Enigma2's "Require authentication for http streams" is on (OpenATV: Menu → Setup → Usage & GUI → Customize System Settings). The stream server checks the receiver's Linux account (usually `root` and its password), not a separate OpenWebif password. Enter that user and password under Ontvangers, or turn the setting off. OpenWebif's "Enable authentication for streaming" only affects its playlists, not this check.
- Own-tuner mode shows no picture on a scrambled channel: the CI module or card is busy with another channel or stream. Pick a free-to-air channel or stop the other stream.
- Picture without OSD: open OSD diagnostics and try PNG OSD, then JPG OSD. Confirm `grab` works in a browser on the same URL.
- OSD without picture: confirm port 8001 is open and the receiver is not in standby. The player reconnects on its own when auto-reconnect is on.
- Zapping on the receiver updates the TV a moment later: that delay is the service poll plus tuner and decoder startup. Sending the zap from the Android remote triggers the fast poll.
- Sound behind the picture: open Settings → Weergave → Geluid synchroniseren and raise the value of the row marked "nu actief" until lips and voice match. Usually 60–200 ms with passthrough to an amplifier or soundbar. Check the TV or amplifier for its own lip-sync or "AV sync" setting first, so the two do not add up.
- Menus feel sluggish: turn on the debug overlay and read OSD latency and frame rate. The receiver's `grab` cost is usually the limit, not Compose.
